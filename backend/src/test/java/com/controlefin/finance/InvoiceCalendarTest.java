package com.controlefin.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.controlefin.finance.FinanceEnums.InvoiceStatus;

/** Calendário das faturas: fechamento, vencimento, "melhor dia de compra" e meses curtos. */
class InvoiceCalendarTest {

    @Test
    void fechaDia3VenceDia10() {
        // Antes do fechamento: fatura que vence no mesmo mês.
        assertEquals(YearMonth.of(2026, 11), InvoiceService.referenceFor(LocalDate.of(2026, 11, 2), 3, 10));
        // No dia do fechamento já vai para a próxima (melhor dia de compra).
        assertEquals(YearMonth.of(2026, 12), InvoiceService.referenceFor(LocalDate.of(2026, 11, 3), 3, 10));
        assertEquals(LocalDate.of(2026, 12, 3), InvoiceService.closingDate(YearMonth.of(2026, 12), 3, 10));
        assertEquals(LocalDate.of(2026, 12, 10), InvoiceService.dueDate(YearMonth.of(2026, 12), 10));
    }

    @Test
    void fechaNoFimDoMesEVenceNoMesSeguinte() {
        assertEquals(YearMonth.of(2026, 12), InvoiceService.referenceFor(LocalDate.of(2026, 11, 27), 28, 5));
        assertEquals(YearMonth.of(2027, 1), InvoiceService.referenceFor(LocalDate.of(2026, 11, 28), 28, 5));
        assertEquals(LocalDate.of(2026, 12, 28), InvoiceService.closingDate(YearMonth.of(2027, 1), 28, 5));
        // Virada de ano.
        assertEquals(YearMonth.of(2027, 2), InvoiceService.referenceFor(LocalDate.of(2026, 12, 31), 28, 5));
    }

    @Test
    void diaInexistenteViraUltimoDiaDoMes() {
        // Fecha dia 31: em fevereiro fecha no dia 28.
        assertEquals(LocalDate.of(2027, 2, 28), InvoiceService.closingDate(YearMonth.of(2027, 3), 31, 8));
        assertEquals(YearMonth.of(2027, 4), InvoiceService.referenceFor(LocalDate.of(2027, 2, 28), 31, 8));
        // Fecha 30 e vence 31: em fevereiro vence dia 28 e fecha um dia antes.
        assertEquals(LocalDate.of(2027, 2, 28), InvoiceService.dueDate(YearMonth.of(2027, 2), 31));
        assertEquals(LocalDate.of(2027, 2, 27), InvoiceService.closingDate(YearMonth.of(2027, 2), 30, 31));
    }

    @Test
    void situacaoDaFatura() throws Exception {
        LocalDate today = LocalDate.of(2026, 11, 24);
        var paid = new InvoiceService.Totals(new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"));
        var unpaid = new InvoiceService.Totals(new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("40.00"));
        Invoice closed = invoice(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 10));
        Invoice closedNotDue = invoice(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 27));
        Invoice open = invoice(LocalDate.of(2026, 12, 3), LocalDate.of(2026, 12, 10));
        Invoice future = invoice(LocalDate.of(2027, 1, 3), LocalDate.of(2027, 1, 10));

        assertEquals(InvoiceStatus.PAGA, InvoiceService.status(closed, paid, today, open.getId()));
        assertEquals(InvoiceStatus.VENCIDA, InvoiceService.status(closed, unpaid, today, open.getId()));
        assertEquals(InvoiceStatus.FECHADA, InvoiceService.status(closedNotDue, unpaid, today, open.getId()));
        assertEquals(InvoiceStatus.ABERTA, InvoiceService.status(open, unpaid, today, open.getId()));
        assertEquals(InvoiceStatus.FUTURA, InvoiceService.status(future, unpaid, today, open.getId()));
    }

    @Test
    void limiteOcupadoIgnoraCompraQueNaoReservaEPagamentoLibera() {
        // Fatura de 300, sendo 100 de assinatura que não reserva limite: ocupa 200.
        var t = new InvoiceService.Totals(new BigDecimal("300.00"), new BigDecimal("100.00"), BigDecimal.ZERO);
        assertEquals(new BigDecimal("200.00"), t.used());
        // Pagou 150: falta 150, dos quais 100 não reservam → ocupa 50.
        t = new InvoiceService.Totals(new BigDecimal("300.00"), new BigDecimal("100.00"), new BigDecimal("150.00"));
        assertEquals(new BigDecimal("50.00"), t.used());
        // Pagou tudo (ou a mais): não ocupa nada.
        t = new InvoiceService.Totals(new BigDecimal("300.00"), new BigDecimal("100.00"), new BigDecimal("350.00"));
        assertEquals(0, t.used().signum());
    }

    @Test
    void pagoAMaisVaiParaAProximaFatura() throws Exception {
        LocalDate today = LocalDate.of(2026, 11, 24);
        CreditLine line = new CreditLine(UUID.randomUUID());
        Invoice nov = invoice(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 10));
        Invoice dec = invoice(LocalDate.of(2026, 12, 3), LocalDate.of(2026, 12, 10));
        set(dec, "referenceMonth", LocalDate.of(2026, 12, 1));
        set(nov, "referenceMonth", LocalDate.of(2026, 11, 1));
        var totals = java.util.Map.of(
                nov.getId(), new InvoiceService.Totals(new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("150.00")),
                dec.getId(), new InvoiceService.Totals(new BigDecimal("80.00"), BigDecimal.ZERO, BigDecimal.ZERO));
        var sums = InvoiceService.summaries(line, java.util.List.of(dec, nov), totals, today);
        assertEquals(InvoiceStatus.PAGA, sums.get(0).status());
        assertEquals(new BigDecimal("50.00"), sums.get(1).carriedCredit());
        assertEquals(new BigDecimal("30.00"), sums.get(1).outstanding());
        assertEquals(new BigDecimal("30.00"), InvoiceService.used(sums));
    }

    @Test
    void compraQueNaoReservaNaoGastaOCreditoLevadoParaAProximaFatura() throws Exception {
        LocalDate today = LocalDate.of(2026, 11, 24);
        CreditLine line = new CreditLine(UUID.randomUUID());
        Invoice dec = invoice(LocalDate.of(2026, 12, 3), LocalDate.of(2026, 12, 10));
        Invoice jan = invoice(LocalDate.of(2027, 1, 3), LocalDate.of(2027, 1, 10));
        set(dec, "referenceMonth", LocalDate.of(2026, 12, 1));
        set(jan, "referenceMonth", LocalDate.of(2027, 1, 1));
        // Dezembro: crédito de 86,15 (estorno + pagamento) que passa para janeiro (parcela de 100).
        var semAssinatura = java.util.Map.of(
                dec.getId(), new InvoiceService.Totals(new BigDecimal("13.85"), BigDecimal.ZERO, new BigDecimal("100.00")),
                jan.getId(), new InvoiceService.Totals(new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO));
        BigDecimal antes = InvoiceService.used(InvoiceService.summaries(line, java.util.List.of(dec, jan), semAssinatura, today));
        assertEquals(new BigDecimal("13.85"), antes);
        // Entra uma assinatura de 118,30 que não reserva limite: o limite usado não muda.
        var comAssinatura = java.util.Map.of(
                dec.getId(), new InvoiceService.Totals(new BigDecimal("132.15"), new BigDecimal("118.30"), new BigDecimal("100.00")),
                jan.getId(), new InvoiceService.Totals(new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ZERO));
        assertEquals(antes, InvoiceService.used(InvoiceService.summaries(line, java.util.List.of(dec, jan), comAssinatura, today)));
    }

    static Invoice invoice(LocalDate closing, LocalDate due) throws Exception {
        Invoice inv = newInvoice(UUID.randomUUID(), UUID.randomUUID(), YearMonth.from(due));
        inv.setClosingDate(closing);
        inv.setDueDate(due);
        return inv;
    }

    static Invoice newInvoice(UUID userId, UUID lineId, YearMonth ref) throws Exception {
        var ctor = Invoice.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Invoice inv = ctor.newInstance();
        set(inv, "id", UUID.randomUUID());
        set(inv, "userId", userId);
        set(inv, "creditLineId", lineId);
        set(inv, "referenceMonth", ref.atDay(1));
        return inv;
    }

    static void set(Object o, String field, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(o, value);
    }
}
