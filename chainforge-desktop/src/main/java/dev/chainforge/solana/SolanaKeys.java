package dev.chainforge.solana;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Minimal Base58 codec for Solana 32-byte public keys and 64-byte keypairs.
 */
public final class SolanaKeys {
    private static final String ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private SolanaKeys() {
    }

    public static String encode(byte[] input) {
        BigInteger n = new BigInteger(1, input);
        StringBuilder out = new StringBuilder();
        while (n.signum() > 0) {
            BigInteger[] qr = n.divideAndRemainder(BigInteger.valueOf(58));
            out.append(ALPHABET.charAt(qr[1].intValue()));
            n = qr[0];
        }
        for (byte b : input) {
            if (b == 0) out.append('1');
            else break;
        }
        return out.reverse().toString();
    }

    public static byte[] decode(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Base58 value is empty.");
        BigInteger n = BigInteger.ZERO;
        for (char c : value.trim().toCharArray()) {
            int d = ALPHABET.indexOf(c);
            if (d < 0) throw new IllegalArgumentException("Invalid Base58 character.");
            n = n.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(d));
        }
        byte[] raw = n.equals(BigInteger.ZERO) ? new byte[0] : n.toByteArray();
        if (raw.length > 0 && raw[0] == 0) raw = Arrays.copyOfRange(raw, 1, raw.length);
        int zeros = 0;
        while (zeros < value.length() && value.charAt(zeros) == '1') zeros++;
        byte[] out = new byte[zeros + raw.length];
        System.arraycopy(raw, 0, out, zeros, raw.length);
        return out;
    }

    public static void validateAddress(String address) {
        if (decode(address).length != 32)
            throw new IllegalArgumentException("Invalid Solana address. Expected a Base58-encoded 32-byte public key.");
    }
}
