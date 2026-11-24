package com.controlefin.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.controlefin.auth.AuthDtos.InviteResponse;
import com.controlefin.auth.AuthDtos.NewInviteResponse;
import com.controlefin.common.ApiException;

/**
 * Convites de cadastro: só entra na plataforma quem recebeu um código de uso único (48 h) gerado pelo administrador.
 * O código é guardado apenas como hash SHA-256.
 */
@Service
public class InviteService {

    private static final Duration VALIDITY = Duration.ofHours(48);
    /** Sem caracteres ambíguos (0/O, 1/I/L). */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final InviteRepository invites;

    public InviteService(InviteRepository invites) {
        this.invites = invites;
    }

    @Transactional
    public NewInviteResponse create(UUID adminId) {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                code.append('-');
            }
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        Invite invite = invites.save(new Invite(hash(code.toString()), adminId, Instant.now().plus(VALIDITY)));
        return new NewInviteResponse(invite.getId(), code.toString(), invite.getExpiresAt());
    }

    /** Consome o convite de forma atômica; falha se ausente, inválido, expirado, revogado ou já usado. */
    @Transactional
    public void consume(String code, String username) {
        if (code == null || code.isBlank()) {
            throw ApiException.badRequest("Informe o código de convite gerado pelo administrador.");
        }
        if (invites.consume(hash(code), username, Instant.now()) != 1) {
            throw ApiException.badRequest("Código de convite inválido, expirado ou já utilizado.");
        }
    }

    @Transactional(readOnly = true)
    public List<InviteResponse> list() {
        Instant now = Instant.now();
        return invites.findTop50ByOrderByCreatedAtDesc().stream()
                .map(i -> new InviteResponse(i.getId(), i.getCreatedAt(), i.getExpiresAt(), i.getUsedBy(),
                        i.getUsedAt(), i.getRevokedAt(), i.status(now)))
                .toList();
    }

    @Transactional
    public void revoke(UUID id) {
        Invite invite = invites.findById(id).orElseThrow(() -> ApiException.notFound("Convite não encontrado."));
        if (invite.getUsedBy() != null) {
            throw ApiException.badRequest("Este convite já foi utilizado.");
        }
        if (invite.getRevokedAt() == null) {
            invite.setRevokedAt(Instant.now());
        }
    }

    static String hash(String code) {
        String normalized = code.trim().toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
