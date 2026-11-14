package com.textbin.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.HexFormat;

/**
 * Cryptographic utility for password hashing and verification using OWASP-recommended
 * PBKDF2WithHmacSHA256 with cryptographically secure salts and constant-time comparison.
 */
public final class PasswordUtil {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 100_000;
    private static final int KEY_LENGTH = 256; // bits
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordUtil() {}

    /**
     * Hashes a raw password using PBKDF2WithHmacSHA256 and a random 16-byte salt.
     * Output format: pbkdf2:<iterations>:<salt_hex>:<hash_hex>
     */
    public static String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Password cannot be null or empty");
        }

        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);

        byte[] hash = pbkdf2(rawPassword.toCharArray(), salt, ITERATIONS, KEY_LENGTH);

        HexFormat hex = HexFormat.of();
        return "pbkdf2:" + ITERATIONS + ":" + hex.formatHex(salt) + ":" + hex.formatHex(hash);
    }

    /**
     * Verifies a candidate raw password against a stored PBKDF2 hash using constant-time comparison.
     */
    public static boolean verify(String candidatePassword, String storedHash) {
        if (candidatePassword == null || candidatePassword.isBlank() || storedHash == null || storedHash.isBlank()) {
            return false;
        }

        String[] parts = storedHash.split(":");
        if (parts.length != 4 || !"pbkdf2".equalsIgnoreCase(parts[0])) {
            return false;
        }

        try {
            int iterations = Integer.parseInt(parts[1]);
            HexFormat hex = HexFormat.of();
            byte[] salt = hex.parseHex(parts[2]);
            byte[] expectedHash = hex.parseHex(parts[3]);

            byte[] actualHash = pbkdf2(candidatePassword.toCharArray(), salt, iterations, expectedHash.length * 8);
            return MessageDigest.isEqual(expectedHash, actualHash);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLength) {
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            KeySpec spec = new PBEKeySpec(password, salt, iterations, keyLength);
            return skf.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 algorithm unavailable", e);
        }
    }
}
