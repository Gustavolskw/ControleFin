package com.controlefin.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.controlefin.auth.PasswordPolicy;

class SecurityPiecesTest {

    private static final String SECRET = "segredo-de-teste-com-mais-de-32-caracteres";

    @Test
    void tokenAssinadoComOutroSegredoEhRecusado() {
        String token = new JwtService("outro-segredo-com-mais-de-32-caracteres!!", 1).generate(UUID.randomUUID(), "x", 0);
        assertTrue(new JwtService(SECRET, 1).validate(token).isEmpty());
    }

    @Test
    void tokenValidoTrazUsuarioEVersao() {
        UUID id = UUID.randomUUID();
        var claims = new JwtService(SECRET, 1).validate(new JwtService(SECRET, 1).generate(id, "x", 3)).orElseThrow();
        assertTrue(claims.userId().equals(id) && claims.tokenVersion() == 3);
    }

    @Test
    void segredoJwtCurtoNaoSobe() {
        assertThrows(IllegalStateException.class, () -> new JwtService("curto", 1));
    }

    @Test
    void rotaInternaExigeSegredoExato() {
        InternalApiFilter filter = new InternalApiFilter(SECRET);
        assertTrue(filter.authorized(SECRET));
        assertFalse(filter.authorized(SECRET + "x"));
        assertFalse(filter.authorized(""));
        assertFalse(filter.authorized(null));
    }

    @Test
    void semSegredoConfiguradoRotasInternasFicamDesligadas() {
        assertFalse(new InternalApiFilter("").authorized(""));
        assertFalse(new InternalApiFilter("").authorized("qualquer"));
    }

    @Test
    void politicaDeSenha() {
        assertTrue(PasswordPolicy.violations("Senha@Forte1", "maria").isEmpty());
        assertFalse(PasswordPolicy.violations("senhafraca", "maria").isEmpty());
        assertFalse(PasswordPolicy.violations("Maria@1234", "maria").isEmpty());
        assertFalse(PasswordPolicy.violations("Com Espaco@1", "x").isEmpty());
    }
}
