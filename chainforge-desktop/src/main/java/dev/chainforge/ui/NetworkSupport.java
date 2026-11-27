package dev.chainforge.ui;

import dev.chainforge.bitcoin.BitcoinKeys;
import dev.chainforge.data.Repository;
import dev.chainforge.evm.EvmAdapter;
import dev.chainforge.solana.SolanaKeys;
import dev.chainforge.xrp.XrpKeys;
import java.util.List;
import java.util.Optional;

import static dev.chainforge.ui.ViewSupport.shortHash;

final class NetworkSupport {
    private NetworkSupport() {
    }

    static Repository.Network networkByKey(String key) throws Exception {
        for (var n : Repository.networks()) if (n.key().equals(key)) return n;
        throw new IllegalArgumentException("Unknown network: " + key);
    }

    /**
     * Enabled networks this wallet can operate on (Bitcoin wallets only match their own network by address prefix).
     */
    static List<Repository.Network> walletNetworks(Repository.Wallet w) {
        try {
            return Repository.networks().stream().filter(x -> x.enabled() && walletMatchesNetwork(w, x)).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    static boolean walletMatchesNetwork(Repository.Wallet w, Repository.Network n) {
        if (!w.family().equalsIgnoreCase(n.family())) return false;
        if ("BITCOIN".equalsIgnoreCase(n.family()))
            return (BitcoinKeys.networkOfAddress(w.address()) == org.bitcoinj.base.BitcoinNetwork.MAINNET) == !n.testnet();
        return true;
    }

    static Optional<Repository.Token> findToken(String contract) {
        try {
            for (var n : Repository.networks())
                for (var t : Repository.tokens(n.key()))
                    if (t.contract().equalsIgnoreCase(contract)) return Optional.of(t);
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    static String explorerTxUrl(String networkKey, String hash) {
        return switch (networkKey) {
            case "bitcoin-testnet" -> "https://mempool.space/testnet/tx/" + hash;
            case "bitcoin-mainnet" -> "https://mempool.space/tx/" + hash;
            case "solana-devnet" -> "https://explorer.solana.com/tx/" + hash + "?cluster=devnet";
            case "solana-mainnet" -> "https://explorer.solana.com/tx/" + hash;
            case "ethereum-mainnet" -> "https://etherscan.io/tx/" + hash;
            case "xrp-testnet" -> "https://testnet.xrpl.org/transactions/" + hash;
            case "xrp-mainnet" -> "https://livenet.xrpl.org/transactions/" + hash;
            case "ethereum-sepolia" -> "https://sepolia.etherscan.io/tx/" + hash;
            default -> {
                // any other EVM network (Avalanche Fuji, custom networks): use the explorer URL saved with the network
                String base = null;
                try {
                    base = networkByKey(networkKey).explorer();
                } catch (Exception ignored) {
                }
                yield base == null || base.isBlank() ? "https://sepolia.etherscan.io/tx/" + hash : base.trim().replaceAll("/+$", "") + "/tx/" + hash;
            }
        };
    }

    static String explorerName(String networkKey) {
        return switch (networkKey) {
            case "bitcoin-testnet" -> "mempool.space (Testnet)";
            case "bitcoin-mainnet" -> "mempool.space";
            case "solana-devnet" -> "Solana Explorer (Devnet)";
            case "solana-mainnet" -> "Solana Explorer";
            case "ethereum-mainnet" -> "Etherscan";
            case "xrp-testnet" -> "XRPL Explorer (Testnet)";
            case "xrp-mainnet" -> "XRPL Explorer";
            case "ethereum-sepolia" -> "Sepolia Etherscan";
            default -> {
                String label = null;
                try {
                    label = networkByKey(networkKey).name();
                } catch (Exception ignored) {
                }
                yield label == null ? "block explorer" : label + " explorer";
            }
        };
    }

    /**
     * Ticker of a network's native coin (AVAX on Avalanche, ETH on Ethereum/Sepolia, ...).
     */
    static String nativeAssetFor(Repository.Network n) {
        return n.nativeSymbol() == null || n.nativeSymbol().isBlank() ? nativeAssetFor(n.family()) : n.nativeSymbol();
    }

    static String nativeAssetFor(String family) {
        if ("SOLANA".equalsIgnoreCase(family)) return "SOL";
        if ("BITCOIN".equalsIgnoreCase(family)) return "BTC";
        if ("XRP".equalsIgnoreCase(family)) return "XRP";
        return "ETH";
    }

    static void validateAddressForNetwork(Repository.Network n, String a) {
        if (n == null) throw new IllegalArgumentException("Select a network.");
        if ("EVM".equalsIgnoreCase(n.family())) validateEvmAddress(a);
        else if ("SOLANA".equalsIgnoreCase(n.family())) SolanaKeys.validateAddress(a);
        else if ("BITCOIN".equalsIgnoreCase(n.family()))
            BitcoinKeys.validateAddress(a, BitcoinKeys.network(n.testnet()));
        else if ("XRP".equalsIgnoreCase(n.family())) XrpKeys.normalizeForNetwork(a, n.testnet());
        else throw new IllegalArgumentException("This network family is not enabled yet.");
    }

    /**
     * Validates a recipient for a network and returns the value to store. On XRP networks an optional destination tag
     * is folded into an X-address (the standard single-string "address + tag" encoding); other networks reject a tag.
     */
    static String storedRecipient(Repository.Network n, String address, String tag) {
        if (n == null) throw new IllegalArgumentException("Select a network.");
        String a = address == null ? "" : address.trim();
        boolean hasTag = tag != null && !tag.isBlank();
        if ("XRP".equalsIgnoreCase(n.family())) {
            if (hasTag) {
                if (XrpKeys.isXAddress(a))
                    throw new IllegalArgumentException("This X-address already contains a destination tag; leave the tag field empty.");
                return XrpKeys.encode(a, XrpKeys.parseTag(tag), n.testnet());
            }
            return XrpKeys.normalizeForNetwork(a, n.testnet());
        }
        if (hasTag) throw new IllegalArgumentException("Destination tags are only used on XRP Ledger networks.");
        validateAddressForNetwork(n, a);
        return a;
    }

    /**
     * Readable recipient text: XRP X-addresses are shown as "rXXXX · tag N", everything else unchanged.
     */
    static String recipientDisplay(String address) {
        return XrpKeys.isXAddress(address) ? XrpKeys.display(address) : address;
    }

    static String shortRecipient(String address) {
        if (!XrpKeys.isXAddress(address)) return shortHash(address);
        try {
            var d = XrpKeys.parseDestination(address);
            return shortHash(d.classic()) + (d.tag() == null ? "" : " #" + d.tag());
        } catch (RuntimeException e) {
            return shortHash(address);
        }
    }

    static void validateEvmAddress(String a) {
        if (a == null || !a.matches("(?i)^0x[0-9a-f]{40}$"))
            throw new IllegalArgumentException("Invalid EVM address. Expected 0x followed by 40 hexadecimal characters.");
    }

    static String assetLabel(String asset) {
        if (!EvmAdapter.isToken(asset)) return asset;
        String contract = EvmAdapter.tokenContract(asset);
        try {
            return findToken(contract).map(t -> t.symbol() + " · " + t.name()).orElse("ERC-20 · " + shortHash(contract));
        } catch (Exception e) {
            return "ERC-20 · " + shortHash(contract);
        }
    }
}
