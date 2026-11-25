package com.controlefin.external;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** A API externa só repassa rotas da lista fechada e só com token no formato certo. */
class ProxyControllerTest {

    private static final String ID = "3f1c2a7e-9b8d-4c6a-a1b2-0c9d8e7f6a5b";
    private static final String TOKEN = "cf_" + "a".repeat(43);

    @Test
    void rotasPermitidas() {
        assertTrue(ProxyController.allowed("GET", "/context"));
        assertTrue(ProxyController.allowed("POST", "/imports"));
        assertTrue(ProxyController.allowed("PUT", "/transactions/" + ID));
        assertTrue(ProxyController.allowed("POST", "/imports/" + ID + "/undo"));
    }

    @Test
    void nadaDeUsuarioLoginOuCaminhoEstranho() {
        assertFalse(ProxyController.allowed("POST", "/auth/login"));
        assertFalse(ProxyController.allowed("GET", "/admin/users"));
        assertFalse(ProxyController.allowed("GET", "/account/tokens"));
        assertFalse(ProxyController.allowed("DELETE", "/transactions/" + ID));
        assertFalse(ProxyController.allowed("GET", "/transactions/../admin/users"));
        assertFalse(ProxyController.allowed("GET", "/transactions/%2e%2e/admin"));
        assertFalse(ProxyController.allowed("PUT", "/context"));
        assertFalse(ProxyController.allowed("GET", "/context/"));
    }

    @Test
    void tokenSoNoFormatoCerto() {
        var req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + TOKEN);
        assertEquals(TOKEN, ProxyController.token(req));

        var other = new MockHttpServletRequest();
        other.addHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.x.y");
        assertNull(ProxyController.token(other));
        assertNull(ProxyController.token(new MockHttpServletRequest()));
    }
}
