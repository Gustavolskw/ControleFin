package com.controlefin.apitoken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.controlefin.audit.AuditService;
import com.controlefin.common.ApiException;
import com.controlefin.user.User;
import com.controlefin.user.UserRepository;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Tokens pessoais de API. Formato: "cf_" + 43 caracteres aleatórios (256 bits). Mostrado uma única vez; o banco
 * guarda só o SHA-256. Validade obrigatória, permissão de leitura ou leitura+escrita, revogação imediata.
 */
@Service
public class ApiTokenService {

    public static final Pattern FORMAT = Pattern.compile("^cf_[A-Za-z0-9_-]{43}$");
    static final Set<Integer> ALLOWED_DAYS = Set.of(30, 90, 180, 365);
    static final int MAX_ACTIVE = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiTokenRepository tokens;
    private final UserRepository users;
    private final AuditService audit;

    public ApiTokenService(ApiTokenRepository tokens, UserRepository users, AuditService audit) {
        this.tokens = tokens;
        this.users = users;
        this.audit = audit;
    }

    public record CreateRequest(@NotBlank @Size(max = 60) String name, @NotNull Integer days, Boolean canWrite) {
    }

    /** O campo token só vem preenchido na criação. */
    public record TokenResponse(UUID id, String name, String prefix, boolean canWrite, Instant createdAt,
                                Instant expiresAt, Instant lastUsedAt, Instant revokedAt, boolean active,
                                String token) {
    }

    /** Usuário e token válidos para uma chamada da API externa. */
    public record Resolved(User user, ApiToken token) {
    }

    @Transactional(readOnly = true)
    public List<TokenResponse> list(UUID userId) {
        Instant now = Instant.now();
        return tokens.findByUserIdOrderByCreatedAtDesc(userId).stream().map(t -> toResponse(t, now, null)).toList();
    }

    @Transactional
    public TokenResponse create(UUID userId, CreateRequest req) {
        if (!ALLOWED_DAYS.contains(req.days())) {
            throw ApiException.badRequest("Validade: 30, 90, 180 ou 365 dias.");
        }
        if (tokens.countActive(userId) >= MAX_ACTIVE) {
            throw ApiException.badRequest("Limite de " + MAX_ACTIVE + " tokens ativos. Revogue algum antes.");
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String value = "cf_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        ApiToken t = new ApiToken(userId, req.name().trim(), value.substring(0, 10), hash(value),
                Boolean.TRUE.equals(req.canWrite()), Instant.now().plus(Duration.ofDays(req.days())));
        tokens.saveAndFlush(t);
        audit.web(userId, "TOKEN_CRIADO", t.getId(), t.getName() + (t.isCanWrite() ? " (leitura e escrita)" : " (leitura)"));
        return toResponse(t, Instant.now(), value);
    }

    @Transactional
    public void revoke(UUID userId, UUID id) {
        ApiToken t = tokens.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Token não encontrado."));
        if (t.getRevokedAt() == null) {
            t.revoke();
            audit.web(userId, "TOKEN_REVOGADO", t.getId(), t.getName());
        }
    }

    /**
     * Valida um token recebido da API externa: formato, hash existente, não revogado, não vencido e usuário ativo
     * (sem primeiro acesso pendente). Qualquer falha = vazio (o chamador responde 401 sem detalhar).
     */
    @Transactional(readOnly = true)
    public Optional<Resolved> resolve(String value) {
        if (value == null || !FORMAT.matcher(value).matches()) return Optional.empty();
        Instant now = Instant.now();
        return tokens.findByTokenHash(hash(value))
                .filter(t -> t.isUsable(now))
                .flatMap(t -> users.findById(t.getUserId())
                        .filter(u -> u.isActive() && !u.isSetupRequired())
                        .map(u -> new Resolved(u, t)));
    }

    public void touch(UUID tokenId) {
        tokens.touch(tokenId);
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static TokenResponse toResponse(ApiToken t, Instant now, String value) {
        return new TokenResponse(t.getId(), t.getName(), t.getPrefix(), t.isCanWrite(), t.getCreatedAt(),
                t.getExpiresAt(), t.getLastUsedAt(), t.getRevokedAt(), t.isUsable(now), value);
    }
}
