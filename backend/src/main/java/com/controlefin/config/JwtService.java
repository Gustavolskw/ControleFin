package com.controlefin.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** Tokens de sessão do site (JWT HS256). Valem só nas rotas /api do site, nunca na api-externa. */
@Service
public class JwtService {

    private static final String AUDIENCE = "controlefin-web";

    private final SecretKey key;
    private final Duration expiration;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-hours}") long expirationHours) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET precisa ter pelo menos 32 caracteres.");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = Duration.ofHours(expirationHours);
    }

    public String generate(UUID userId, String username, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .audience().add(AUDIENCE).and()
                .claim("username", username)
                .claim("ver", tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(key)
                .compact();
    }

    /** Retorna o usuário e a versão do token se a assinatura, a validade e o público estiverem corretos. */
    public Optional<TokenClaims> validate(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).requireAudience(AUDIENCE).build()
                    .parseSignedClaims(token).getPayload();
            Integer version = claims.get("ver", Integer.class);
            return Optional.of(new TokenClaims(UUID.fromString(claims.getSubject()), version == null ? -1 : version));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record TokenClaims(UUID userId, int tokenVersion) {
    }
}
