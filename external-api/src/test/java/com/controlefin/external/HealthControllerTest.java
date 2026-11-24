package com.controlefin.external;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HealthControllerTest {

    @Test
    void healthNaoExpoeDados() {
        assertEquals(java.util.Map.of("status", "ok"), new HealthController().health());
    }
}
