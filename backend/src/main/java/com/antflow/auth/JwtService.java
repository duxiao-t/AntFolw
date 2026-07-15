package com.antflow.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Service
public class JwtService {

    private final SecretKey key;
    private final long ttlSeconds;
    private final long clockSkewSeconds;

    public JwtService(JwtProperties props) {
        String secret = props.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                "antflow.jwt.secret (env JWT_SECRET) must be set; refusing to start");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                "antflow.jwt.secret must be at least 32 bytes; got " + bytes.length);
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttlSeconds = props.getTtlSeconds();
        this.clockSkewSeconds = props.getClockSkewSeconds();
    }

    public String issue(Long userId, String username, List<String> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
            .subject(String.valueOf(userId))
            .claim("username", username)
            .claim("roles", roles)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plusSeconds(ttlSeconds)))
            .signWith(key)
            .compact();
    }

    public Claims parse(String token) throws JwtException {
        Jws<Claims> jws = Jwts.parser()
            .verifyWith(key)
            .clockSkewSeconds(clockSkewSeconds)
            .build()
            .parseSignedClaims(token);
        return jws.getPayload();
    }
}
