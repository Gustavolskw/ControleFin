package com.controlefin.user;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 40)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.USER;

    @Column(nullable = false)
    private boolean active = true;

    /** Usuário padrão criado no primeiro start: precisa trocar a senha inicial do .env. */
    @Column(name = "password_change_required", nullable = false)
    private boolean passwordChangeRequired;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_security_questions", joinColumns = @JoinColumn(name = "user_id"))
    @OrderColumn(name = "position")
    private List<SecurityQuestion> securityQuestions = new ArrayList<>();

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "failed_recovery_attempts", nullable = false)
    private int failedRecoveryAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    /** Versão dos tokens: ao trocar a senha, incrementa e todos os tokens antigos deixam de valer. */
    @Column(name = "token_version", nullable = false)
    private int tokenVersion;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Primeiro acesso pendente: trocar a senha inicial e/ou cadastrar as perguntas de segurança. */
    public boolean isSetupRequired() {
        return passwordChangeRequired || securityQuestions == null || securityQuestions.isEmpty();
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    /** Todos os tokens emitidos até agora deixam de valer. */
    public void invalidateTokens() {
        tokenVersion++;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isPasswordChangeRequired() { return passwordChangeRequired; }
    public void setPasswordChangeRequired(boolean passwordChangeRequired) { this.passwordChangeRequired = passwordChangeRequired; }
    public List<SecurityQuestion> getSecurityQuestions() { return securityQuestions; }
    public void setSecurityQuestions(List<SecurityQuestion> questions) {
        this.securityQuestions.clear();
        this.securityQuestions.addAll(questions);
    }
    public int getFailedLoginAttempts() { return failedLoginAttempts; }
    public void setFailedLoginAttempts(int failedLoginAttempts) { this.failedLoginAttempts = failedLoginAttempts; }
    public int getFailedRecoveryAttempts() { return failedRecoveryAttempts; }
    public void setFailedRecoveryAttempts(int failedRecoveryAttempts) { this.failedRecoveryAttempts = failedRecoveryAttempts; }
    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }
    public int getTokenVersion() { return tokenVersion; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Instant getCreatedAt() { return createdAt; }
}
