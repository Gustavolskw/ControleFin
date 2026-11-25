package com.controlefin.apitoken;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Token pessoal de API (usado pelo Claude/MCP pela API externa). Só o hash SHA-256 fica gravado. */
@Entity
@Table(name = "api_tokens")
public class ApiToken {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 60)
    private String name;

    /** Começo do token (ex.: "cf_a1B2c3D"), para o usuário reconhecer qual é. */
    @Column(nullable = false, length = 12, updatable = false)
    private String prefix;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "can_write", nullable = false)
    private boolean canWrite;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ApiToken() {
    }

    public ApiToken(UUID userId, String name, String prefix, String tokenHash, boolean canWrite, Instant expiresAt) {
        this.userId = userId;
        this.name = name;
        this.prefix = prefix;
        this.tokenHash = tokenHash;
        this.canWrite = canWrite;
        this.expiresAt = expiresAt;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getName() { return name; }
    public String getPrefix() { return prefix; }
    public boolean isCanWrite() { return canWrite; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void revoke() { this.revokedAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
}
