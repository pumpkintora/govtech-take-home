package com.chat.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {

    @Test
    void hashesWithUniqueSaltAndVerifiesCorrectly() {
        String h1 = PasswordHasher.hashPassword("super-secret-123");
        String h2 = PasswordHasher.hashPassword("super-secret-123");

        assertNotEquals(h1, h2);
        assertTrue(PasswordHasher.verifyPassword("super-secret-123", h1));
        assertTrue(PasswordHasher.verifyPassword("super-secret-123", h2));
        assertFalse(PasswordHasher.verifyPassword("wrong-password", h1));
    }
}
