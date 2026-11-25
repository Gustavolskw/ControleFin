package com.controlefin.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.controlefin.common.ApiException;
import com.controlefin.common.Money;
import com.controlefin.finance.FinanceDtos.TransactionRequest;
import com.controlefin.finance.FinanceEnums.CardFormat;
import com.controlefin.finance.FinanceEnums.CardFunction;
import com.controlefin.finance.FinanceEnums.Origin;
import com.controlefin.finance.FinanceEnums.TransactionKind;
import com.controlefin.finance.FinanceEnums.TransactionStatus;

/** Compras no cartão: fatura pela data, parcelas, débito, moeda estrangeira, pagamento de fatura e estorno. */
class CardPurchaseTest {

    private final UUID me = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private TransactionRepository transactions;
    private TransactionService service;
    private Wallet wallet;
    private Card credit;
    private Card debitOnly;
    private CreditLine line;
    /** Faturas "gravadas" (por mês de vencimento). */
    private final Map<LocalDate, Invoice> stored = new HashMap<>();
    /** Lançamentos "gravados". */
    private final List<Transaction> saved = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        transactions = mock(TransactionRepository.class);
        WalletRepository wallets = mock(WalletRepository.class);
        CardRepository cards = mock(CardRepository.class);
        CreditLineRepository lines = mock(CreditLineRepository.class);
        InvoiceRepository invoices = mock(InvoiceRepository.class);
        service = new TransactionService(transactions, wallets, mock(AccountRepository.class),
                mock(CategoryRepository.class), cards, lines, invoices, new InvoiceService(invoices, lines), mock(RecurrenceRepository.class));

        wallet = withId(new Wallet(me, UUID.randomUUID()));
        wallet.setName("Principal");
        line = withId(new CreditLine(me));
        line.setName("Nubank");
        line.setClosingDay(3);
        line.setDueDay(10);
        line.setLimitAmount(new BigDecimal("5000.00"));
        credit = withId(new Card(me));
        credit.setName("Nubank físico");
        credit.setFormat(CardFormat.FISICO);
        credit.setFunction(CardFunction.CREDITO_DEBITO);
        credit.setCreditLineId(line.getId());
        credit.setWalletId(wallet.getId());
        debitOnly = withId(new Card(me));
        debitOnly.setName("Wiipo VA");
        debitOnly.setFormat(CardFormat.FISICO);
        debitOnly.setFunction(CardFunction.VOUCHER_ALIMENTACAO);
        debitOnly.setWalletId(wallet.getId());

        when(wallets.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());
        when(wallets.findByIdAndUserId(wallet.getId(), me)).thenReturn(Optional.of(wallet));
        when(cards.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());
        when(cards.findByIdAndUserId(credit.getId(), me)).thenReturn(Optional.of(credit));
        when(cards.findByIdAndUserId(debitOnly.getId(), me)).thenReturn(Optional.of(debitOnly));
        when(lines.findByIdAndUserId(line.getId(), me)).thenReturn(Optional.of(line));
        when(lines.findByUserIdOrderByNameAsc(me)).thenReturn(List.of(line));

        when(invoices.findByCreditLineIdAndReferenceMonth(eq(line.getId()), any()))
                .thenAnswer(i -> Optional.ofNullable(stored.get(i.<LocalDate>getArgument(1))));
        when(invoices.createIfMissing(any(), any(), any(), any(), any())).thenAnswer(i -> {
            LocalDate ref = i.getArgument(2);
            Invoice inv = InvoiceCalendarTest.newInvoice(me, line.getId(), YearMonth.from(ref));
            inv.setClosingDate(i.getArgument(3));
            inv.setDueDate(i.getArgument(4));
            stored.putIfAbsent(ref, inv);
            return 1;
        });
        when(invoices.findByIdAndUserId(any(), eq(me))).thenAnswer(i -> stored.values().stream()
                .filter(inv -> inv.getId().equals(i.getArgument(0))).findFirst());
        when(invoices.findByUserIdOrderByReferenceMonthAsc(me)).thenAnswer(i -> new ArrayList<>(stored.values()));

        when(transactions.saveAllAndFlush(any())).thenAnswer(i -> {
            List<Transaction> rows = i.getArgument(0);
            for (Transaction t : rows) {
                if (t.getId() == null) InvoiceCalendarTest.set(t, "id", UUID.randomUUID());
            }
            saved.addAll(rows);
            return rows;
        });
        when(transactions.save(any())).thenAnswer(i -> {
            Transaction t = i.getArgument(0);
            InvoiceCalendarTest.set(t, "id", UUID.randomUUID());
            saved.add(t);
            return t;
        });
        when(transactions.findByIdAndUserId(any(), eq(me))).thenAnswer(i -> saved.stream()
                .filter(t -> t.getId().equals(i.getArgument(0))).findFirst());
        when(transactions.findByUserIdAndInstallmentGroupOrderByInstallmentNumberAsc(eq(me), any()))
                .thenAnswer(i -> saved.stream().filter(t -> i.getArgument(1).equals(t.getInstallmentGroup())).toList());
        when(transactions.existsByUserIdAndReversalOfIn(eq(me), anyCollection())).thenAnswer(i -> saved.stream()
                .anyMatch(t -> t.getReversalOf() != null && i.<java.util.Collection<UUID>>getArgument(1).contains(t.getReversalOf())));
    }

    private TransactionRequest purchase(LocalDate date, String amount, UUID cardId, Boolean credit, Integer installments,
                                        Boolean consumesLimit) {
        return new TransactionRequest(TransactionKind.DESPESA, null, date, "Compra", new BigDecimal(amount), null, null,
                null, null, cardId, credit, installments, consumesLimit, null, null, null, null, null, null, null);
    }

    private Invoice invoiceOf(Transaction t) {
        return stored.values().stream().filter(i -> i.getId().equals(t.getInvoiceId())).findFirst().orElseThrow();
    }

    @Test
    void compraNoCreditoCaiNaFaturaPelaDataENaoUsaCarteira() {
        var before = service.create(me, purchase(LocalDate.of(2026, 11, 2), "50", credit.getId(), true, null, null), Origin.WEB);
        var onClosing = service.create(me, purchase(LocalDate.of(2026, 11, 3), "50", credit.getId(), true, null, null), Origin.WEB);
        assertNull(before.walletId());
        assertEquals("2026-11", before.invoiceReference());
        assertEquals("2026-12", onClosing.invoiceReference());
        assertEquals("Nubank", before.creditLineName());
    }

    @Test
    void parceladoDivideCentavosEUsaFaturasSeguintes() {
        service.create(me, purchase(LocalDate.of(2026, 11, 10), "100", credit.getId(), true, 3, null), Origin.WEB);
        assertEquals(3, saved.size());
        assertEquals(new BigDecimal("33.34"), saved.get(0).getAmount());
        assertEquals(new BigDecimal("33.33"), saved.get(1).getAmount());
        assertEquals(new BigDecimal("33.33"), saved.get(2).getAmount());
        assertEquals(YearMonth.of(2026, 12), invoiceOf(saved.get(0)).getReference());
        assertEquals(YearMonth.of(2027, 1), invoiceOf(saved.get(1)).getReference());
        assertEquals(YearMonth.of(2027, 2), invoiceOf(saved.get(2)).getReference());
        assertEquals(LocalDate.of(2027, 1, 10), saved.get(2).getDate());
        assertEquals(saved.get(0).getInstallmentGroup(), saved.get(2).getInstallmentGroup());
        assertEquals(3, saved.get(2).getInstallmentNumber());
    }

    @Test
    void debitoSaiDaCarteiraDoCartao() {
        var r = service.create(me, purchase(LocalDate.of(2026, 11, 10), "20", credit.getId(), false, null, null), Origin.WEB);
        assertEquals(wallet.getId(), r.walletId());
        assertNull(r.invoiceId());
        var v = service.create(me, purchase(LocalDate.of(2026, 11, 10), "30", debitOnly.getId(), null, null, null), Origin.WEB);
        assertEquals(wallet.getId(), v.walletId());
    }

    @Test
    void cartaoSemCreditoNaoAceitaCompraNoCredito() {
        assertThrows(ApiException.class, () -> service.create(me,
                purchase(LocalDate.of(2026, 11, 10), "20", debitOnly.getId(), true, null, null), Origin.WEB));
    }

    @Test
    void parcelarSoNoCredito() {
        assertThrows(ApiException.class, () -> service.create(me,
                purchase(LocalDate.of(2026, 11, 10), "20", credit.getId(), false, 2, null), Origin.WEB));
    }

    @Test
    void assinaturaQueNaoReservaLimite() {
        var r = service.create(me, purchase(LocalDate.of(2026, 11, 10), "110", credit.getId(), true, null, false), Origin.WEB);
        assertFalse(r.consumesLimit());
    }

    @Test
    void moedaEstrangeiraCalculaReaisComIof() {
        var req = new TransactionRequest(TransactionKind.DESPESA, null, LocalDate.of(2026, 11, 10), "Claude Pro", null,
                null, null, null, null, credit.getId(), true, null, false, null, "usd", new BigDecimal("20.00"),
                new BigDecimal("5.50"), new BigDecimal("3.85"), null, null);
        var r = service.create(me, req, Origin.WEB);
        assertEquals(new BigDecimal("113.85"), r.amount());
        assertEquals("USD", r.originalCurrency());

        var parcelado = new TransactionRequest(TransactionKind.DESPESA, null, LocalDate.of(2026, 11, 10), "X", null,
                null, null, null, null, credit.getId(), true, 2, null, null, "USD", new BigDecimal("20.00"),
                new BigDecimal("5.50"), null, null, null);
        assertThrows(ApiException.class, () -> service.create(me, parcelado, Origin.WEB));
    }

    @Test
    void pagamentoDeFaturaSaiDaCarteiraEAbateAFatura() {
        var compra = service.create(me, purchase(LocalDate.of(2026, 11, 10), "80", credit.getId(), true, null, null), Origin.WEB);
        var pay = new TransactionRequest(TransactionKind.PAGAMENTO_FATURA, null, LocalDate.of(2026, 12, 5), "Fatura",
                new BigDecimal("80"), wallet.getId(), null, null, null, null, null, null, null, compra.invoiceId(),
                null, null, null, null, null, null);
        var r = service.create(me, pay, Origin.WEB);
        assertEquals(wallet.getId(), r.walletId());
        assertEquals(compra.invoiceId(), r.invoiceId());

        var semFatura = new TransactionRequest(TransactionKind.PAGAMENTO_FATURA, null, LocalDate.of(2026, 12, 5), "Fatura",
                new BigDecimal("80"), wallet.getId(), null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);
        assertThrows(ApiException.class, () -> service.create(me, semFatura, Origin.WEB));
    }

    @Test
    void cartaoDeOutroUsuarioNaoExiste() {
        ApiException ex = assertThrows(ApiException.class, () -> service.create(other,
                purchase(LocalDate.of(2026, 11, 10), "20", credit.getId(), true, null, null), Origin.WEB));
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    void estornoCancelaParcelasFuturasEDevolveOJaCobrado() {
        LocalDate today = Money.today();
        // 1ª parcela em fatura já fechada; as demais em faturas futuras.
        var first = service.create(me, purchase(today.minusMonths(2), "300", credit.getId(), true, 6, null), Origin.WEB);
        var r = service.reverse(me, first.id(), null);

        assertEquals(TransactionKind.ESTORNO, r.kind());
        assertEquals(first.id(), r.reversalOf());
        assertNotNull(r.invoiceId());
        long cancelled = saved.stream().filter(t -> t.getStatus() == TransactionStatus.CANCELADO).count();
        BigDecimal kept = saved.stream()
                .filter(t -> t.getKind() == TransactionKind.DESPESA && t.getStatus() == TransactionStatus.CONFIRMADO)
                .map(Transaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(kept, r.amount(), "o estorno devolve exatamente o que já foi cobrado");
        assertEquals(6, cancelled + saved.stream().filter(t -> t.getKind() == TransactionKind.DESPESA
                && t.getStatus() == TransactionStatus.CONFIRMADO).count());

        // Não estorna duas vezes.
        assertThrows(ApiException.class, () -> service.reverse(me, first.id(), null));
    }

    @Test
    void estornoDeCompraFuturaSoCancela() {
        var r = service.create(me, purchase(Money.today().plusMonths(2), "50", credit.getId(), true, null, null), Origin.WEB);
        var rev = service.reverse(me, r.id(), null);
        assertEquals(TransactionStatus.CANCELADO, rev.status());
        assertEquals(1, saved.size(), "nenhum estorno criado");
    }

    private static <T> T withId(T entity) throws Exception {
        InvoiceCalendarTest.set(entity, "id", UUID.randomUUID());
        return entity;
    }
}
