package com.antflow.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceConstructorTest {

    @Test void rejectsMissingSecret() {
        JwtProperties p = new JwtProperties();
        p.setSecret("");
        p.setTtlSeconds(60);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new JwtService(p));
        assertTrue(e.getMessage().contains("JWT_SECRET"));
    }

    @Test void rejectsShortSecret() {
        JwtProperties p = new JwtProperties();
        p.setSecret("short"); // 5 bytes
        p.setTtlSeconds(60);
        assertThrows(IllegalStateException.class, () -> new JwtService(p));
    }

    @Test void acceptsLongSecret() {
        JwtProperties p = new JwtProperties();
        p.setSecret("0123456789abcdef0123456789abcdef"); // 32 bytes
        p.setTtlSeconds(60);
        assertDoesNotThrow(() -> new JwtService(p));
    }
}
