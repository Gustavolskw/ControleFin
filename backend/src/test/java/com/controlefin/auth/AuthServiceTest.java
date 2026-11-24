package com.controlefin.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.controlefin.auth.AuthDtos.ChangePasswordRequest;
import com.controlefin.auth.AuthDtos.LoginRequest;
import com.controlefin.common.ApiException;
import com.controlefin.config.JwtService;
import com.controlefin.user.User;
import com.controlefin.user.UserRepository;

class AuthServiceTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final JwtService jwt = new JwtService("segredo-de-teste-com-mais-de-32-caracteres", 1);
    private UserRepository users;
    private AuthService service;
    private User user;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        service = new AuthService(users, encoder, jwt, mock(InviteService.class), 5, 15);
        user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("maria");
        user.setPasswordHash(encoder.encode("Senha@Forte1"));
        when(users.findByUsername(anyString())).thenReturn(Optional.empty());
        when(users.findByUsername("maria")).thenReturn(Optional.of(user));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
    }

    @Test
    void cincoSenhasErradasBloqueiamAConta() {
        for (int i = 0; i < 5; i++) {
            assertThrows(ApiException.class, () -> service.login(new LoginRequest("maria", "errada")));
        }
        assertNotNull(user.getLockedUntil());
        ApiException ex = assertThrows(ApiException.class,
                () -> service.login(new LoginRequest("maria", "Senha@Forte1")));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
    }

    @Test
    void usuarioInexistenteTemAMesmaMensagemDeSenhaErrada() {
        ApiException a = assertThrows(ApiException.class, () -> service.login(new LoginRequest("ninguem", "x")));
        ApiException b = assertThrows(ApiException.class, () -> service.login(new LoginRequest("maria", "x")));
        assertEquals(a.getMessage(), b.getMessage());
        assertEquals(a.getStatus(), b.getStatus());
    }

    @Test
    void contaDesativadaNaoEntra() {
        user.setActive(false);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.login(new LoginRequest("maria", "Senha@Forte1")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    @Test
    void trocarSenhaInvalidaTokensAntigos() {
        String antigo = service.login(new LoginRequest("maria", "Senha@Forte1")).token();
        int versaoAntiga = jwt.validate(antigo).orElseThrow().tokenVersion();

        String novo = service.changePassword(user.getId(),
                new ChangePasswordRequest("Senha@Forte1", "Outra#Senha2")).token();

        assertTrue(user.getTokenVersion() > versaoAntiga);
        assertEquals(user.getTokenVersion(), jwt.validate(novo).orElseThrow().tokenVersion());
    }

    @Test
    void respostaIgnoraMaiusculasAcentosEEspacos() {
        assertEquals(AuthService.normalizeAnswer("  São   PAULO "), AuthService.normalizeAnswer("sao paulo"));
    }
}
