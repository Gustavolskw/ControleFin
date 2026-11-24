package com.controlefin.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;

/** Dinheiro sempre em BigDecimal com 2 casas (nunca double). Datas no fuso do Brasil. */
public final class Money {

    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private Money() {
    }

    public static BigDecimal of(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_EVEN);
    }

    public static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2) : value.setScale(2, RoundingMode.HALF_EVEN);
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** Texto opcional: espaços nas pontas removidos; vazio vira null. */
    public static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
