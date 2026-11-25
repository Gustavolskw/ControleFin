package com.controlefin.apitoken;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.controlefin.audit.AuditService;
import com.controlefin.common.ApiException;
import com.controlefin.user.User;
import com.controlefin.user.UserRepository;

/** Tokens de API: formato, só o hash gravado, validade, revogação e usuário ativo. */
class ApiTokenServiceTest {

    private ApiTokenRepository repo;
    private UserRepository users;
    private ApiTokenService service;
    private final UUID me = UUID.randomUUID();
    private final AtomicReference<ApiToken> saved = new AtomicReference<>();
    private User user;

    @BeforeEach
    void setUp() throws Exception {
        repo = mock(ApiTokenRepository.class);
        users = mock(UserRepository.class);
        service = new ApiTokenService(repo, users, mock(AuditService.class));
        when(repo.saveAndFlush(any())).thenAnswer(i -> {
            ApiToken t = i.getArgument(0);
            set(t, "id", UUID.randomUUID());
            saved.set(t);
            return t;
        });
        user = mock(User.class);
        when(user.getId()).thenReturn(me);
        when(user.isActive()).thenReturn(true);
        when(user.isSetupRequired()).thenReturn(false);
        when(users.findById(me)).thenReturn(Optional.of(user));
    }

    private String createToken(boolean write) {
        var r = service.create(me, new ApiTokenService.CreateRequest("Claude celular", 90, write));
        when(repo.findByTokenHash(ApiTokenService.hash(r.token()))).thenReturn(Optional.of(saved.get()));
        return r.token();
    }

    @Test
    void tokenTemFormatoForteEOBancoGuardaSoOHash() {
        String token = createToken(true);
        assertTrue(ApiTokenService.FORMAT.matcher(token).matches(), token);
        ApiToken stored = saved.get();
        assertEquals(64, ApiTokenService.hash(token).length());
        assertFalse(stored.getPrefix().length() >= token.length());
        assertTrue(token.startsWith(stored.getPrefix()));
        // O hash gravado não é o token.
        assertFalse(ApiTokenService.hash(token).contains(token.substring(3)));
    }

    @Test
    void validadeSoNosValoresPermitidos() {
        assertThrows(ApiException.class, () -> service.create(me, new ApiTokenService.CreateRequest("x", 10000, false)));
    }

    @Test
    void resolveAceitaTokenValido() {
        String token = createToken(false);
        var r = service.resolve(token);
        assertTrue(r.isPresent());
        assertFalse(r.get().token().isCanWrite());
    }

    @Test
    void recusaFormatoInvalidoSemConsultarOBanco() {
        assertTrue(service.resolve("Bearer abc").isEmpty());
        assertTrue(service.resolve(null).isEmpty());
        verify(repo, never()).findByTokenHash(any());
    }

    @Test
    void recusaRevogadoVencidoEUsuarioInativo() throws Exception {
        String token = createToken(true);
        saved.get().revoke();
        assertTrue(service.resolve(token).isEmpty());

        String t2 = createToken(true);
        set(saved.get(), "expiresAt", Instant.now().minus(Duration.ofMinutes(1)));
        assertTrue(service.resolve(t2).isEmpty());

        String t3 = createToken(true);
        when(user.isActive()).thenReturn(false);
        assertTrue(service.resolve(t3).isEmpty());
    }

    @Test
    void tokenDesconhecido() {
        String fake = "cf_" + "A".repeat(43);
        when(repo.findByTokenHash(any())).thenReturn(Optional.empty());
        assertTrue(service.resolve(fake).isEmpty());
        assertNotNull(ApiTokenService.hash(fake));
    }

    private static void set(Object o, String field, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, value);
    }
}
