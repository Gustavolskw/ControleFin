package com.controlefin.common;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Único ponto de onde se obtém o usuário logado. Todo acesso a dados filtra por este id,
 * nunca por um id vindo da requisição — é isso que impede um usuário de ver os dados de outro.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal principal)) {
            throw ApiException.unauthorized("Não autenticado.");
        }
        return principal.userId();
    }

    /** Token pessoal de API usado nesta requisição (null quando veio do site). */
    public static UUID tokenId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthPrincipal p ? p.tokenId() : null;
    }

    /**
     * Usuário autenticado colocado no contexto de segurança pelos filtros de autenticação.
     * tokenId: preenchido quando a chamada veio da API externa (token pessoal).
     */
    public record AuthPrincipal(UUID userId, String username, UUID tokenId) {
        public AuthPrincipal(UUID userId, String username) {
            this(userId, username, null);
        }
    }
}
