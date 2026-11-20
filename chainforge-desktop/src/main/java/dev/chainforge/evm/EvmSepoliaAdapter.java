package dev.chainforge.evm;

import dev.chainforge.core.ChainAdapter;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Sepolia native ETH and ERC-20 adapter. Token asset syntax: ERC20:<contract-address>.
 */
public final class EvmSepoliaAdapter implements ChainAdapter {
    private static final String RPC = "https://ethereum-sepolia-rpc.publicnode.com";
    private static final BigInteger CHAIN_ID = BigInteger.valueOf(11155111);

    private Web3j client() {
        return Web3j.build(new HttpService(RPC));
    }

    @Override
    public String family() {
        return "EVM";
    }

    @Override
    public String networkKey() {
        return "ethereum-sepolia";
    }

    public record TokenInfo(String contract, String symbol, int decimals, String name) {
    }

    public static String tokenAsset(String contract) {
        validateAddress(contract);
        return "ERC20:" + contract;
    }

    public static boolean isToken(String asset) {
        return asset != null && asset.toUpperCase().startsWith("ERC20:");
    }

    public static String tokenContract(String asset) {
        if (!isToken(asset)) throw new IllegalArgumentException("Not an ERC-20 asset");
        return asset.substring(asset.indexOf(':') + 1).trim();
    }

    private static void validateAddress(String a) {
        if (a == null || !a.matches("(?i)^0x[0-9a-f]{40}$"))
            throw new IllegalArgumentException("Invalid ERC-20 contract address.");
    }

    public CompletableFuture<TokenInfo> tokenInfo(String contract) {
        return CompletableFuture.supplyAsync(() -> {
            validateAddress(contract);
            try (Web3j w = client()) {
                String name = callString(w, contract, "name");
                String symbol = callString(w, contract, "symbol");
                Function f = new Function("decimals", List.of(), List.of(new TypeReference<Uint8>() {
                }));
                List<Type> out = FunctionReturnDecoder.decode(w.ethCall(Transaction.createEthCallTransaction(null, contract, FunctionEncoder.encode(f)), DefaultBlockParameterName.LATEST).send().getValue(), f.getOutputParameters());
                if (out.isEmpty()) throw new IllegalStateException("Token contract did not return decimals().");
                int decimals = ((BigInteger) out.get(0).getValue()).intValueExact();
                if (decimals < 0 || decimals > 36)
                    throw new IllegalStateException("Unsupported token decimals: " + decimals);
                return new TokenInfo(contract, symbol, decimals, name);
            } catch (Exception e) {
                throw new RuntimeException("Could not read ERC-20 metadata: " + root(e), e);
            }
        });
    }

    private String callString(Web3j w, String contract, String method) throws Exception {
        Function f = new Function(method, List.of(), List.of(new TypeReference<Utf8String>() {
        }));
        var response = w.ethCall(Transaction.createEthCallTransaction(null, contract, FunctionEncoder.encode(f)), DefaultBlockParameterName.LATEST).send();
        if (response.hasError()) throw new IllegalStateException(response.getError().getMessage());
        List<Type> out = FunctionReturnDecoder.decode(response.getValue(), f.getOutputParameters());
        if (out.isEmpty()) throw new IllegalStateException("Contract did not return " + method + "().");
        return String.valueOf(out.get(0).getValue());
    }

    @Override
    public CompletableFuture<String> getBalance(String address, String asset) {
        return CompletableFuture.supplyAsync(() -> {
            try (Web3j w = client()) {
                if (isToken(asset)) {
                    String contract = tokenContract(asset);
                    TokenInfo info = tokenInfo(contract).join();
                    Function f = new Function("balanceOf", List.of(new Address(address)), List.of(new TypeReference<Uint256>() {
                    }));
                    var res = w.ethCall(Transaction.createEthCallTransaction(address, contract, FunctionEncoder.encode(f)), DefaultBlockParameterName.LATEST).send();
                    if (res.hasError()) throw new IllegalStateException(res.getError().getMessage());
                    List<Type> out = FunctionReturnDecoder.decode(res.getValue(), f.getOutputParameters());
                    if (out.isEmpty()) throw new IllegalStateException("balanceOf returned no value.");
                    return new BigDecimal((BigInteger) out.get(0).getValue()).movePointLeft(info.decimals()).stripTrailingZeros().toPlainString();
                }
                var b = w.ethGetBalance(address, DefaultBlockParameterName.LATEST).send();
                return Convert.fromWei(new BigDecimal(b.getBalance()), Convert.Unit.ETHER).stripTrailingZeros().toPlainString();
            } catch (Exception e) {
                throw new RuntimeException(root(e), e);
            }
        });
    }

    @Override
    public CompletableFuture<TransferResult> transfer(String privateKey, String to, BigDecimal amount, String asset) {
        return CompletableFuture.supplyAsync(() -> {
            try (Web3j w = client()) {
                Credentials credentials = Credentials.create(privateKey);
                if (isToken(asset)) {
                    String contract = tokenContract(asset);
                    validateAddress(to);
                    TokenInfo info = tokenInfo(contract).join();
                    BigInteger units = amount.movePointRight(info.decimals()).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact();
                    if (units.signum() <= 0)
                        throw new IllegalArgumentException("Token amount is below the smallest unit.");
                    Function f = new Function("transfer", List.of(new Address(to), new Uint256(units)), List.of(new TypeReference<org.web3j.abi.datatypes.Bool>() {
                    }));
                    String data = FunctionEncoder.encode(f);
                    BigInteger nonce = w.ethGetTransactionCount(credentials.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
                    BigInteger gasPrice = w.ethGasPrice().send().getGasPrice();
                    BigInteger gasLimit = BigInteger.valueOf(100_000);
                    var estimate = w.ethEstimateGas(Transaction.createFunctionCallTransaction(credentials.getAddress(), nonce, gasPrice, gasLimit, contract, data)).send();
                    if (!estimate.hasError() && estimate.getAmountUsed() != null)
                        gasLimit = estimate.getAmountUsed().multiply(BigInteger.valueOf(120)).divide(BigInteger.valueOf(100));
                    RawTransaction tx = RawTransaction.createTransaction(nonce, gasPrice, gasLimit, contract, BigInteger.ZERO, data);
                    byte[] signed = TransactionEncoder.signMessage(tx, CHAIN_ID.longValueExact(), credentials);
                    var sent = w.ethSendRawTransaction(Numeric.toHexString(signed)).send();
                    if (sent.hasError())
                        throw new IllegalStateException("ERC-20 broadcast rejected: " + sent.getError().getMessage());
                    String hash = sent.getTransactionHash();
                    if (hash == null || hash.isBlank())
                        throw new IllegalStateException("RPC did not return a transaction hash.");
                    return awaitReceipt(w, hash);
                }
                if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be positive.");
                var receipt = org.web3j.tx.Transfer.sendFunds(w, credentials, to, amount, Convert.Unit.ETHER).send();
                return new TransferResult(receipt.getTransactionHash(), receipt.isStatusOK() ? "CONFIRMED" : "FAILED", receipt.getGasUsed() + " gas");
            } catch (Exception e) {
                throw new RuntimeException(root(e), e);
            }
        });
    }

    @Override
    public CompletableFuture<StatusResult> checkTransactionStatus(String hash) {
        return CompletableFuture.supplyAsync(() -> {
            try (Web3j w = client()) {
                var receiptResponse = w.ethGetTransactionReceipt(hash).send();
                if (receiptResponse.hasError())
                    throw new IllegalStateException(receiptResponse.getError().getMessage());
                var receipt = receiptResponse.getTransactionReceipt();
                if (receipt.isPresent()) {
                    var r = receipt.get();
                    return new StatusResult(r.isStatusOK() ? "CONFIRMED" : "FAILED",
                            r.getGasUsed() == null ? null : r.getGasUsed().toString() + " gas",
                            r.isStatusOK() ? null : "Transaction reverted on-chain.");
                }
                var txResponse = w.ethGetTransactionByHash(hash).send();
                if (txResponse.hasError()) throw new IllegalStateException(txResponse.getError().getMessage());
                // A missing receipt is not proof of failure; keep it pending even if this RPC
                // has not indexed the transaction yet.
                return new StatusResult("PENDING", null, null);
            } catch (Exception e) {
                throw new RuntimeException("Could not query Sepolia transaction status: " + root(e), e);
            }
        });
    }

    private TransferResult awaitReceipt(Web3j w, String hash) throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            var r = w.ethGetTransactionReceipt(hash).send().getTransactionReceipt();
            if (r.isPresent()) {
                var receipt = r.get();
                return new TransferResult(hash, receipt.isStatusOK() ? "CONFIRMED" : "FAILED", receipt.getGasUsed() + " gas");
            }
            Thread.sleep(1500);
        }
        return new TransferResult(hash, "PENDING", "receipt not observed within 90 seconds");
    }

    private static String root(Throwable e) {
        while (e.getCause() != null && e.getCause() != e) e = e.getCause();
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
