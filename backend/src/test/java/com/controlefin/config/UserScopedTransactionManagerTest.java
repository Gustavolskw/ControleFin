package com.controlefin.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.controlefin.common.CurrentUser.AuthPrincipal;

class UserScopedTransactionManagerTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void semUsuarioLogadoNenhumaLinhaFinanceiraFicaVisivel() {
        assertEquals("", UserScopedTransactionManager.currentUserId());
    }

    @Test
    void usuarioLogadoVaiParaORowLevelSecurity() {
        UUID id = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new AuthPrincipal(id, "maria"), null, List.of()));
        assertEquals(id.toString(), UserScopedTransactionManager.currentUserId());
    }
}
