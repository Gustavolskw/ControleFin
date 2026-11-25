package com.controlefin.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.controlefin.common.ApiException;
import com.controlefin.finance.FinanceDtos.RecurrenceRequest;
import com.controlefin.finance.FinanceEnums.CardFormat;
import com.controlefin.finance.FinanceEnums.CardFunction;
import com.controlefin.finance.FinanceEnums.Frequency;
import com.controlefin.finance.FinanceEnums.TransactionKind;

/** Recorrências: calendário das ocorrências, regras de cadastro e de vínculo (sem duplicar ocorrência). */
class RecurrenceTest {

    private final UUID me = UUID.randomUUID();

    private static Recurrence rule(Frequency f, int interval, Integer day, LocalDate start, LocalDate end) {
        Recurrence r = new Recurrence(UUID.randomUUID());
        r.setFrequency(f);
        r.setIntervalCount(interval);
        r.setDayOfMonth(day);
        r.setStartDate(start);
        r.setEndDate(end);
        r.setKind(TransactionKind.DESPESA);
        r.setDescription("Claude Pro");
        r.setAmount(new BigDecimal("110.00"));
        return r;
    }

    @Test
    void mensalNoDia31UsaOUltimoDiaDoMes() {
        var r = rule(Frequency.MENSAL, 1, 31, LocalDate.of(2026, 1, 31), null);
        assertEquals(List.of(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31)),
                RecurrenceService.occurrences(r, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)));
    }

    @Test
    void mensalRespeitaInicioFimEIntervalo() {
        var r = rule(Frequency.MENSAL, 2, 5, LocalDate.of(2026, 1, 10), LocalDate.of(2026, 9, 1));
        // Começa em 10/01: a de 05/01 não existe; a cada 2 meses: mar, mai, jul (set passa do fim).
        assertEquals(List.of(LocalDate.of(2026, 3, 5), LocalDate.of(2026, 5, 5), LocalDate.of(2026, 7, 5)),
                RecurrenceService.occurrences(r, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31)));
        assertTrue(RecurrenceService.occursOn(r, LocalDate.of(2026, 5, 5)));
        assertFalse(RecurrenceService.occursOn(r, LocalDate.of(2026, 4, 5)));
    }

    @Test
    void anualESemanal() {
        var anual = rule(Frequency.ANUAL, 1, 29, LocalDate.of(2024, 2, 29), null);
        assertEquals(List.of(LocalDate.of(2025, 2, 28), LocalDate.of(2026, 2, 28)),
                RecurrenceService.occurrences(anual, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31)));
        var semanal = rule(Frequency.SEMANAL, 1, null, LocalDate.of(2026, 11, 2), null);
        assertEquals(List.of(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 30)),
                RecurrenceService.occurrences(semanal, LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 30)));
    }

    @Test
    void descricaoParecida() {
        assertTrue(RecurrenceService.similar("Claude Pro", "CLAUDE.AI SUBSCRIPTION"));
        assertFalse(RecurrenceService.similar("Aluguel", "Mercado"));
    }

    @Test
    void ocorrenciaJaLancadaNaoPodeSerLancadaDeNovo() {
        TransactionRepository tx = mock(TransactionRepository.class);
        var r = rule(Frequency.MENSAL, 1, 5, LocalDate.of(2026, 1, 5), null);
        LocalDate date = LocalDate.of(2026, 11, 5);
        when(tx.occurrenceTaken(eq(me), eq(r.getId()), eq(date), any())).thenReturn(true);
        ApiException ex = assertThrows(ApiException.class,
                () -> RecurrenceService.checkOccurrence(tx, me, r, date, TransactionKind.DESPESA, false, null));
        assertEquals(409, ex.getStatus().value());
        // Data que não é ocorrência, tipo diferente e parcelado são recusados.
        assertThrows(ApiException.class, () -> RecurrenceService.checkOccurrence(tx, me, r, LocalDate.of(2026, 11, 6),
                TransactionKind.DESPESA, false, null));
        assertThrows(ApiException.class, () -> RecurrenceService.checkOccurrence(tx, me, r, LocalDate.of(2026, 12, 5),
                TransactionKind.RECEITA, false, null));
        assertThrows(ApiException.class, () -> RecurrenceService.checkOccurrence(tx, me, r, LocalDate.of(2026, 12, 5),
                TransactionKind.DESPESA, true, null));
    }

    @Test
    void cadastroValidaContaCartaoEDadosDeMoeda() throws Exception {
        RecurrenceRepository repo = mock(RecurrenceRepository.class);
        WalletRepository wallets = mock(WalletRepository.class);
        CardRepository cards = mock(CardRepository.class);
        var service = new RecurrenceService(repo, mock(TransactionRepository.class), wallets,
                mock(AccountRepository.class), cards, mock(CategoryRepository.class), null);
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(wallets.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());
        when(cards.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());

        Card voucher = new Card(me);
        InvoiceCalendarTest.set(voucher, "id", UUID.randomUUID());
        voucher.setName("Wiipo VA");
        voucher.setFormat(CardFormat.FISICO);
        voucher.setFunction(CardFunction.VOUCHER_ALIMENTACAO);
        voucher.setWalletId(UUID.randomUUID());
        when(cards.findByIdAndUserId(voucher.getId(), me)).thenReturn(Optional.of(voucher));

        // Nem conta nem cartão.
        assertThrows(ApiException.class, () -> service.create(me, req(null, null, null, null, null)));
        // Voucher não tem crédito.
        assertThrows(ApiException.class, () -> service.create(me, req(null, voucher.getId(), true, null, null)));
        // Moeda sem valor original.
        assertThrows(ApiException.class, () -> service.create(me, req(null, voucher.getId(), false, "USD", null)));
        // Cartão de outro usuário.
        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(UUID.randomUUID(), req(null, voucher.getId(), false, null, null)));
        assertEquals(404, ex.getStatus().value());
    }

    private static RecurrenceRequest req(UUID walletId, UUID cardId, Boolean credit, String currency, BigDecimal orig) {
        return new RecurrenceRequest(TransactionKind.DESPESA, "Assinatura", new BigDecimal("110"), Frequency.MENSAL, 1,
                5, LocalDate.of(2026, 1, 5), null, walletId, cardId, credit, null, null, currency, orig, null, null);
    }
}
