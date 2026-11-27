package dev.chainforge.xrp;

import org.xrpl.xrpl4j.codec.addresses.AddressCodec;
import org.xrpl.xrpl4j.codec.addresses.Decoded;
import org.xrpl.xrpl4j.codec.addresses.SeedCodec;
import org.xrpl.xrpl4j.crypto.keys.Base58EncodedSecret;
import org.xrpl.xrpl4j.crypto.keys.KeyPair;
import org.xrpl.xrpl4j.crypto.keys.Seed;
import com.google.common.primitives.UnsignedInteger;
import org.xrpl.xrpl4j.model.transactions.Address;
import org.xrpl.xrpl4j.model.transactions.XAddress;

/**
 * XRP Ledger key helpers. A wallet is a base58 family seed ("s…" secp256k1 or "sEd…" ed25519). The
 * classic address ("r…") derived from a seed is identical on every XRPL network, so one wallet works on
 * both the testnet and the mainnet.
 */
public final class XrpKeys {
    public record Generated(String seed, String address) {
    }

    /** A payment destination: a classic address plus an optional destination tag (null = no tag). */
    public record Destination(String classic, Long tag) {
    }

    public static final long MAX_TAG = 4_294_967_295L;

    private XrpKeys() {
    }

    /** New random wallet using Ed25519 (the current XRPL default). */
    public static Generated generate() {
        Seed seed = Seed.ed25519Seed();
        try {
            return new Generated(encode(seed), address(seed.deriveKeyPair()));
        } finally {
            destroyQuietly(seed);
        }
    }

    private static void destroyQuietly(Seed seed) {
        try {
            seed.destroy();
        } catch (javax.security.auth.DestroyFailedException ignored) {
            // best effort: wiping the seed bytes is a hygiene measure, not a correctness requirement
        }
    }

    static String encode(Seed seed) {
        Decoded d = seed.decodedSeed();
        return SeedCodec.getInstance().encodeSeed(d.bytes(), d.type().orElseThrow(() -> new IllegalStateException("Unknown seed key type")));
    }

    /** Parses a family seed (secp256k1 or ed25519) into a key pair. */
    public static KeyPair keyPair(String seedString) {
        if (seedString == null || seedString.isBlank()) throw new IllegalArgumentException("XRP seed is empty.");
        try {
            Seed seed = Seed.fromBase58EncodedSecret(Base58EncodedSecret.of(seedString.trim()));
            try {
                return seed.deriveKeyPair();
            } finally {
                destroyQuietly(seed);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a valid XRP family seed (expected a base58 string starting with 's'). Account secret keys / mnemonics are not supported.", e);
        }
    }

    public static String address(KeyPair keyPair) {
        return keyPair.publicKey().deriveAddress().value();
    }

    public static String addressOfSeed(String seedString) {
        return address(keyPair(seedString));
    }

    /** Classic r-addresses only. X-addresses (which embed a destination tag) are rejected. */
    public static void validateAddress(String address) {
        if (address == null || address.isBlank()) throw new IllegalArgumentException("XRP address is empty.");
        String a = address.trim();
        if ((a.startsWith("X") || a.startsWith("T")) && a.length() >= 46)
            throw new IllegalArgumentException("X-addresses (destination-tag addresses) are not supported. Use the classic address starting with 'r'.");
        boolean ok;
        try {
            ok = a.startsWith("r") && AddressCodec.getInstance().isValidClassicAddress(Address.of(a));
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) throw new IllegalArgumentException("Invalid XRP classic address. Expected a base58 address starting with 'r'.");
    }

    private static boolean looksLikeXAddress(String a) {
        return (a.startsWith("X") || a.startsWith("T")) && a.length() >= 46;
    }

    public static boolean isXAddress(String a) {
        return a != null && looksLikeXAddress(a.trim());
    }

    /** Parses a recipient: either a classic r-address (no tag) or an X-address (address + destination tag in one string). */
    public static Destination parseDestination(String input) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException("XRP address is empty.");
        String a = input.trim();
        if (looksLikeXAddress(a)) {
            try {
                var codec = AddressCodec.getInstance();
                XAddress x = XAddress.of(a);
                if (!codec.isValidXAddress(x)) throw new IllegalArgumentException("Invalid X-address.");
                var decoded = codec.decodeXAddress(x);
                String classic = codec.encodeAccountId(decoded.accountId()).value();
                long tag = decoded.tag().longValue();
                // xrpl4j reports a tag-less X-address as tag 0. Tell it apart from a genuine tag 0 by re-encoding.
                if (tag == 0) {
                    String withZero = codec.classicAddressToXAddress(Address.of(classic), UnsignedInteger.ZERO, decoded.test()).value();
                    if (!withZero.equals(a)) return new Destination(classic, null);
                }
                return new Destination(classic, tag);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid XRP X-address.", e);
            }
        }
        validateAddress(a);
        return new Destination(a, null);
    }

    public static long parseTag(String tag) {
        String t = tag == null ? "" : tag.trim();
        if (!t.matches("\\d{1,10}")) throw new IllegalArgumentException("Destination tag must be a whole number between 0 and " + MAX_TAG + ".");
        long v = Long.parseLong(t);
        if (v > MAX_TAG) throw new IllegalArgumentException("Destination tag must be between 0 and " + MAX_TAG + ".");
        return v;
    }

    /**
     * Canonical stored form of a recipient: the classic address when there is no tag, otherwise the X-address
     * (test networks use the "T…" form, mainnet the "X…" form).
     */
    public static String encode(String classic, Long tag, boolean testnet) {
        validateAddress(classic);
        if (tag == null) return classic.trim();
        if (tag < 0 || tag > MAX_TAG) throw new IllegalArgumentException("Destination tag must be between 0 and " + MAX_TAG + ".");
        return AddressCodec.getInstance().classicAddressToXAddress(Address.of(classic.trim()), UnsignedInteger.valueOf(tag), testnet).value();
    }

    /**
     * Normalises user input for a network: a classic address stays as is; an X-address must match the
     * network (T… for test networks, X… for mainnet) and is returned unchanged.
     */
    public static String normalizeForNetwork(String input, boolean testnet) {
        Destination d = parseDestination(input);
        String a = input.trim();
        if (looksLikeXAddress(a)) {
            boolean isTestForm = a.startsWith("T");
            if (isTestForm != testnet)
                throw new IllegalArgumentException(testnet ? "This X-address is for mainnet (starts with 'X'), but this is a test network." : "This X-address is for test networks (starts with 'T'), but this is mainnet.");
            return a;
        }
        return d.classic();
    }

    /** Human readable form: "rXXXX · tag 12345", or the plain address when there is no tag. */
    public static String display(String stored) {
        try {
            Destination d = parseDestination(stored);
            return d.tag() == null ? d.classic() : d.classic() + " · tag " + d.tag();
        } catch (RuntimeException e) {
            return stored;
        }
    }
}
