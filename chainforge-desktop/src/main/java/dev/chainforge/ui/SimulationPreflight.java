package dev.chainforge.ui;

import dev.chainforge.bitcoin.BitcoinAdapter;
import dev.chainforge.core.ChainAdapter;
import dev.chainforge.data.Repository;
import dev.chainforge.evm.EvmAdapter;
import dev.chainforge.solana.SolanaAdapter;
import dev.chainforge.xrp.XrpAdapter;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import static dev.chainforge.ui.NetworkSupport.assetLabel;
import static dev.chainforge.ui.NetworkSupport.nativeAssetFor;
import static dev.chainforge.ui.NetworkSupport.networkByKey;
import static dev.chainforge.ui.NetworkSupport.recipientDisplay;
import static dev.chainforge.ui.NetworkSupport.storedRecipient;
import static dev.chainforge.ui.NetworkSupport.walletMatchesNetwork;

final class SimulationPreflight {
    private final Function<Repository.Network, ChainAdapter> adapterFor;

    SimulationPreflight(Function<Repository.Network, ChainAdapter> adapterFor) {
        this.adapterFor = adapterFor;
    }

    record SimulationPlan(Repository.Wallet wallet, Repository.Network network, ChainAdapter adapter,
                                  String asset, List<Repository.PlannedTransfer> transfers, long minDelay,
                                  long maxDelay,
                                  BigDecimal total, BigDecimal assetBalance, BigDecimal nativeBalance, BigDecimal fee) {
    }

    SimulationPlan prepare(Repository.Wallet wallet, Repository.Network network, String asset,
                           long minDelay, long maxDelay, List<Repository.Recipient> recipients,
                           BigDecimal low, BigDecimal high, List<Repository.Tx> retryRows) throws Exception {
        Repository.Network latest = networkByKey(network.key());
        if (!latest.equals(network) || !latest.enabled() || !walletMatchesNetwork(wallet, latest)
                || Repository.wallets().stream().noneMatch(candidate -> candidate.id() == wallet.id()))
            throw new IllegalStateException("The sender or network changed. Select them again.");
        String nativeAsset = nativeAssetFor(latest);
        ChainAdapter adapter = adapterFor.apply(latest);
        boolean token = adapter instanceof EvmAdapter && EvmAdapter.isToken(asset);
        if (!token && !nativeAsset.equalsIgnoreCase(asset))
            throw new IllegalArgumentException("Select a supported asset.");
        int decimals = switch (adapter.family().toUpperCase(Locale.ROOT)) {
            case "EVM" ->
                    token ? ((EvmAdapter) adapter).tokenInfo(EvmAdapter.tokenContract(asset)).join().decimals() : 18;
            case "SOLANA" -> 9;
            case "XRP" -> 6;
            default -> 8;
        };
        var transfers = new ArrayList<Repository.PlannedTransfer>();
        if (retryRows.isEmpty()) {
            var minUnits = amountUnits(low, decimals);
            var maxUnits = amountUnits(high, decimals);
            var range = maxUnits.subtract(minUnits).add(java.math.BigInteger.ONE);
            var random = new SecureRandom();
            for (var recipient : recipients) {
                java.math.BigInteger offset;
                do {
                    offset = new java.math.BigInteger(range.bitLength(), random);
                } while (offset.compareTo(range) >= 0);
                transfers.add(new Repository.PlannedTransfer(recipient.address(), new BigDecimal(minUnits.add(offset), decimals), null));
            }
        } else {
            for (var row : retryRows) {
                if (!"PLANNED".equals(row.status())
                        && !"FAILED".equalsIgnoreCase(adapter.checkTransactionStatus(row.hash()).join().status()))
                    throw new IllegalStateException("Could not verify the previous failure for " + row.to() + ".");
                var amount = new BigDecimal(row.amount());
                amountUnits(amount, decimals);
                transfers.add(new Repository.PlannedTransfer(row.to(), amount, row.id()));
            }
        }
        Set<String> addresses = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var transfer : transfers) {
            String address = simulationDestination(latest, transfer.address());
            String canonical = switch (latest.family().toUpperCase(Locale.ROOT)) {
                case "EVM" -> address.toLowerCase(Locale.ROOT);
                case "XRP" -> recipientDisplay(address);
                default -> address;
            };
            if (!addresses.add(canonical))
                throw new IllegalArgumentException("Duplicate recipient: " + recipientDisplay(address));
            total = total.add(transfer.amount());
        }
        BigDecimal nativeBalance = new BigDecimal(adapter.getBalance(wallet.address(), nativeAsset).join());
        BigDecimal assetBalance = token ? new BigDecimal(adapter.getBalance(wallet.address(), asset).join()) : nativeBalance;
        if (assetBalance.compareTo(total) < 0)
            throw new IllegalArgumentException("Insufficient " + assetLabel(asset) + " balance for this plan.");
        if (nativeBalance.signum() <= 0 || (!token && nativeBalance.compareTo(total) <= 0))
            throw new IllegalArgumentException("Leave a " + nativeAsset + " balance for network fees.");
        BigDecimal fee = switch (adapter) {
            case EvmAdapter evm -> evm.estimateBatchFee(wallet.address(), asset, decimals, transfers.stream()
                    .map(t -> new EvmAdapter.FeeTarget(t.address(), t.amount())).toList());
            case BitcoinAdapter bitcoin -> bitcoin.estimateBatchFee(wallet.address(), transfers.stream()
                    .map(Repository.PlannedTransfer::amount).toList());
            case XrpAdapter xrp -> xrp.estimateBatchFee(wallet.address(), total, transfers.size());
            case SolanaAdapter solana -> solana.estimateBatchFee(transfers.size());
            default -> null;
        };
        if (fee != null && nativeBalance.compareTo(token ? fee : total.add(fee)) < 0)
            throw new IllegalArgumentException("Insufficient " + nativeAsset + " to cover estimated network fees.");
        return new SimulationPlan(wallet, latest, adapter, asset, List.copyOf(transfers), minDelay, maxDelay,
                total, assetBalance, nativeBalance, fee);
    }

    private static java.math.BigInteger amountUnits(BigDecimal amount, int decimals) {
        try {
            if (amount.signum() <= 0 || amount.stripTrailingZeros().scale() > decimals
                    || (long) amount.precision() - amount.scale() + decimals > 78)
                throw new ArithmeticException();
            var units = amount.setScale(decimals, java.math.RoundingMode.UNNECESSARY).unscaledValue();
            if (units.bitLength() > 256) throw new ArithmeticException();
            return units;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Enter a positive amount with at most " + decimals + " decimal places.");
        }
    }

    static String simulationDestination(Repository.Network network, String stored) {
        if ("XRP".equalsIgnoreCase(network.family()) && stored.contains(" · tag ")) {
            String[] original = stored.split(" · tag ", 2);
            return storedRecipient(network, original[0], original[1]);
        }
        return storedRecipient(network, stored, "");
    }
}
