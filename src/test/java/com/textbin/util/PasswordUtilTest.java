package com.textbin.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordUtilTest {

    @Test
    void testHashAndVerifySuccess() {
        String password = "mySecretPassword123!";
        String hash = PasswordUtil.hash(password);

        assertNotNull(hash);
        assertTrue(hash.startsWith("pbkdf2:100000:"));

        assertTrue(PasswordUtil.verify(password, hash));
    }

    @Test
    void testVerifyFailureOnWrongPassword() {
        String password = "correctPassword";
        String hash = PasswordUtil.hash(password);

        assertFalse(PasswordUtil.verify("wrongPassword", hash));
        assertFalse(PasswordUtil.verify("", hash));
        assertFalse(PasswordUtil.verify(null, hash));
    }

    @Test
    void testEmptyPasswordRejected() {
        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.hash(""));
        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.hash("   "));
        assertThrows(IllegalArgumentException.class, () -> PasswordUtil.hash(null));
    }
}
