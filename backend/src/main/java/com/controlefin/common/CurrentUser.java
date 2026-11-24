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

    /** Usuário autenticado colocado no contexto de segurança pelos filtros de autenticação. */
    public record AuthPrincipal(UUID userId, String username) {
    }
}
