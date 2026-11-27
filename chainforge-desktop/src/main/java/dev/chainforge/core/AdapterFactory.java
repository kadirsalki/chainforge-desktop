package dev.chainforge.core;

import dev.chainforge.bitcoin.BitcoinAdapter;
import dev.chainforge.bitcoin.BitcoinKeys;
import dev.chainforge.data.Repository;
import dev.chainforge.evm.EvmAdapter;
import dev.chainforge.solana.SolanaAdapter;
import dev.chainforge.xrp.XrpAdapter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds (and caches) one live adapter per configured network, using the RPC / API URL and chain id
 * stored in the networks table. Changing the RPC in the Networks page therefore takes effect on the
 * next call; adapters with state (Bitcoin's recently-spent tracking) are reused while the config is unchanged.
 */
public final class AdapterFactory {
    private final Map<String, ChainAdapter> cache = new ConcurrentHashMap<>();

    public ChainAdapter forNetwork(Repository.Network n) {
        String cacheKey = n.key() + "|" + n.family() + "|" + n.rpc() + "|" + n.chainId() + "|" + n.testnet();
        return cache.computeIfAbsent(cacheKey, k -> create(n));
    }

    public static boolean isSupported(Repository.Network n) {
        String f = n.family() == null ? "" : n.family().toUpperCase();
        return f.equals("EVM") || f.equals("SOLANA") || f.equals("BITCOIN") || f.equals("XRP");
    }

    private static ChainAdapter create(Repository.Network n) {
        switch (n.family().toUpperCase()) {
            case "EVM": {
                long chainId;
                try {
                    chainId = Long.parseLong(n.chainId().trim());
                } catch (Exception e) {
                    throw new IllegalArgumentException("Network " + n.name() + " has no valid numeric chain ID.");
                }
                // Routescan serves an Etherscan-compatible history API (no key) for many EVM chains, mainnet and testnet.
                return new EvmAdapter(n.key(), n.rpc(), chainId)
                        .withIndexer("https://api.routescan.io/v2/network/" + (n.testnet() ? "testnet" : "mainnet") + "/evm/" + chainId + "/etherscan/api");
            }
            case "SOLANA":
                return new SolanaAdapter(n.key(), n.rpc());
            case "XRP":
                return new XrpAdapter(n.key(), n.rpc()).withTestnet(n.testnet());
            case "BITCOIN":
                return new BitcoinAdapter(n.key(), n.rpc(), BitcoinKeys.network(n.testnet()));
            default:
                throw new IllegalArgumentException("This network family has no live adapter yet: " + n.family());
        }
    }
}
