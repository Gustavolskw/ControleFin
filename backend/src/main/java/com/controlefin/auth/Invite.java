package com.controlefin.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Convite de cadastro (uso único, validade limitada). O código fica guardado só como hash SHA-256. */
@Entity
@Table(name = "invites")
public class Invite {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_by", length = 40)
    private String usedBy;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected Invite() {
    }

    public Invite(String codeHash, UUID createdBy, Instant expiresAt) {
        this.codeHash = codeHash;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
    }

    public String status(Instant now) {
        if (usedBy != null) return "USADO";
        if (revokedAt != null) return "REVOGADO";
        if (!expiresAt.isAfter(now)) return "EXPIRADO";
        return "PENDENTE";
    }

    public UUID getId() { return id; }
    public String getCodeHash() { return codeHash; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getUsedBy() { return usedBy; }
    public Instant getUsedAt() { return usedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
