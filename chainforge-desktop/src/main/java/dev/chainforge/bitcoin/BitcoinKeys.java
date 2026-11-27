package dev.chainforge.bitcoin;

import org.bitcoinj.base.Address;
import org.bitcoinj.base.AddressParser;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.crypto.DumpedPrivateKey;
import org.bitcoinj.crypto.ECKey;

/**
 * Key and address helpers for Bitcoin. Wallets use native SegWit (P2WPKH: bc1q... on mainnet,
 * tb1q... on testnet) and are stored as compressed WIF private keys. Every method takes the
 * network explicitly so a testnet key/address can never be accepted on mainnet or vice versa.
 */
public final class BitcoinKeys {
    public record ParsedWif(ECKey key, BitcoinNetwork network) {
    }

    private BitcoinKeys() {
    }

    public static BitcoinNetwork network(boolean testnet) {
        return testnet ? BitcoinNetwork.TESTNET : BitcoinNetwork.MAINNET;
    }

    public static String label(BitcoinNetwork n) {
        return n == BitcoinNetwork.MAINNET ? "Mainnet" : "Testnet";
    }

    public static ECKey generate() {
        return new ECKey(new java.security.SecureRandom());
    }

    public static String toWif(ECKey key, BitcoinNetwork net) {
        return key.getPrivateKeyEncoded(net).toString();
    }

    /**
     * Parses a WIF key for exactly the given network; a key from another network is rejected.
     */
    public static ECKey fromWif(String wif, BitcoinNetwork net) {
        if (wif == null || wif.isBlank()) throw new IllegalArgumentException("Bitcoin private key is empty.");
        try {
            ECKey key = DumpedPrivateKey.fromBase58(net, wif.trim()).getKey();
            if (!key.isCompressed())
                throw new IllegalArgumentException("Only compressed WIF keys are supported (native SegWit).");
            return key;
        } catch (org.bitcoinj.base.exceptions.AddressFormatException e) {
            throw new IllegalArgumentException("Not a valid Bitcoin " + label(net) + " WIF private key (keys from another network are rejected). " + e.getMessage(), e);
        }
    }

    /**
     * Detects the network from the WIF prefix (mainnet K/L/5, testnet c/9).
     */
    public static ParsedWif parseWif(String wif) {
        for (BitcoinNetwork net : new BitcoinNetwork[]{BitcoinNetwork.MAINNET, BitcoinNetwork.TESTNET}) {
            try {
                return new ParsedWif(fromWif(wif, net), net);
            } catch (IllegalArgumentException ignored) {
                // try the next network
            }
        }
        throw new IllegalArgumentException("Not a valid compressed Bitcoin WIF private key (mainnet or testnet).");
    }

    public static String address(ECKey key, BitcoinNetwork net) {
        return key.toAddress(ScriptType.P2WPKH, net).toString();
    }

    /**
     * Network an existing address string belongs to, judged by its prefix.
     */
    public static BitcoinNetwork networkOfAddress(String address) {
        String a = address == null ? "" : address.trim().toLowerCase(java.util.Locale.ROOT);
        return a.startsWith("bc1") || a.startsWith("1") || a.startsWith("3") ? BitcoinNetwork.MAINNET : BitcoinNetwork.TESTNET;
    }

    public static Address parseAddress(String address, BitcoinNetwork net) {
        if (address == null || address.isBlank()) throw new IllegalArgumentException("Bitcoin address is empty.");
        try {
            return AddressParser.getDefault(net).parseAddress(address.trim());
        } catch (org.bitcoinj.base.exceptions.AddressFormatException e) {
            String expected = net == BitcoinNetwork.MAINNET ? "bc1... / 1... / 3..." : "tb1... / m... / n... / 2...";
            throw new IllegalArgumentException("Invalid Bitcoin " + label(net) + " address (addresses from another network are rejected). Expected " + expected, e);
        }
    }

    public static void validateAddress(String address, BitcoinNetwork net) {
        parseAddress(address, net);
    }
}
