package dev.chainforge.security;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-256-GCM at-rest encryption. The master password is never persisted.
 */
public final class WalletVault {
    public record Sealed(byte[] ciphertext, byte[] salt) {
    }

    private static final int ITERATIONS = 310_000;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private WalletVault() {
    }

    public static Sealed seal(char[] password, byte[] plain) throws GeneralSecurityException {
        try {
            byte[] salt = new byte[SALT_BYTES];
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(salt);
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plain);
            byte[] packed = new byte[NONCE_BYTES + encrypted.length];
            System.arraycopy(nonce, 0, packed, 0, NONCE_BYTES);
            System.arraycopy(encrypted, 0, packed, NONCE_BYTES, encrypted.length);
            return new Sealed(packed, salt);
        } finally {
            Arrays.fill(password, (char) 0);
        }
    }

    public static byte[] open(char[] password, byte[] sealed, byte[] salt) throws GeneralSecurityException {
        try {
            if (sealed == null || sealed.length <= NONCE_BYTES)
                throw new GeneralSecurityException("Invalid encrypted wallet data.");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(128, sealed, 0, NONCE_BYTES));
            return cipher.doFinal(sealed, NONCE_BYTES, sealed.length - NONCE_BYTES);
        } finally {
            Arrays.fill(password, (char) 0);
        }
    }

    private static SecretKey deriveKey(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try {
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(raw, "AES");
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }
}
