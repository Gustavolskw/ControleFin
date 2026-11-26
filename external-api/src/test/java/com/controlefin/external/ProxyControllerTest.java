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
        assertTrue(ProxyController.allowed("POST", "/accounts"));
        assertTrue(ProxyController.allowed("PUT", "/wallets/" + ID));
        assertTrue(ProxyController.allowed("PUT", "/categories/" + ID));
        assertTrue(ProxyController.allowed("POST", "/credit-lines"));
        assertTrue(ProxyController.allowed("PUT", "/cards/" + ID));
        assertTrue(ProxyController.allowed("POST", "/recurrences/" + ID + "/occurrences/2026-12-05/skip"));
    }

    @Test
    void auxiliaresSemExclusao() {
        for (String p : new String[] {"/accounts/", "/wallets/", "/categories/", "/credit-lines/", "/cards/",
                "/recurrences/"}) {
            assertFalse(ProxyController.allowed("DELETE", p + ID), p);
        }
        assertFalse(ProxyController.allowed("DELETE", "/recurrences/" + ID + "/skips/2026-12-05"));
        assertFalse(ProxyController.allowed("POST", "/recurrences/" + ID + "/occurrences/12-2026/skip"));
        assertFalse(ProxyController.allowed("DELETE", "/investments/" + ID));
        assertFalse(ProxyController.allowed("DELETE", "/investments/" + ID + "/movements/" + ID));
    }

    @Test
    void investimentos() {
        assertTrue(ProxyController.allowed("GET", "/investments"));
        assertTrue(ProxyController.allowed("POST", "/investments"));
        assertTrue(ProxyController.allowed("PUT", "/investments/" + ID));
        assertTrue(ProxyController.allowed("POST", "/investments/" + ID + "/movements"));
        assertTrue(ProxyController.allowed("PUT", "/investments/" + ID + "/movements/" + ID));
    }

    @Test
    void nadaDeUsuarioLoginOuCaminhoEstranho() {
        assertFalse(ProxyController.allowed("POST", "/auth/login"));
        assertFalse(ProxyController.allowed("GET", "/admin/users"));
        assertFalse(ProxyController.allowed("GET", "/account/tokens"));
        assertFalse(ProxyController.allowed("POST", "/account/tokens"));
        assertFalse(ProxyController.allowed("PUT", "/account/password"));
        assertFalse(ProxyController.allowed("GET", "/account/activity"));
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
