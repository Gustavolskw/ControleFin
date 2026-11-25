package com.controlefin.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.controlefin.common.ApiException;
import com.controlefin.finance.FinanceDtos.TransactionRequest;
import com.controlefin.finance.FinanceEnums.CategoryKind;
import com.controlefin.finance.FinanceEnums.Origin;
import com.controlefin.finance.FinanceEnums.TransactionKind;

/** Regras dos lançamentos e isolamento: nada de outro usuário é aceito. */
class TransactionRulesTest {

    private final UUID me = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private TransactionRepository transactions;
    private WalletRepository wallets;
    private CategoryRepository categories;
    private TransactionService service;
    private Wallet wallet;
    private Wallet wallet2;
    private Category expense;
    private Category income;

    @BeforeEach
    void setUp() throws Exception {
        transactions = mock(TransactionRepository.class);
        wallets = mock(WalletRepository.class);
        categories = mock(CategoryRepository.class);
        AccountRepository accounts = mock(AccountRepository.class);
        InvoiceRepository invoices = mock(InvoiceRepository.class);
        CreditLineRepository lines = mock(CreditLineRepository.class);
        service = new TransactionService(transactions, wallets, accounts, categories, mock(CardRepository.class),
                lines, invoices, new InvoiceService(invoices, lines));

        UUID accountId = UUID.randomUUID();
        wallet = withId(new Wallet(me, accountId));
        wallet.setName("Principal");
        wallet2 = withId(new Wallet(me, accountId));
        wallet2.setName("VA/VR");
        expense = withId(new Category(me, CategoryKind.DESPESA, "Mercado", null));
        income = withId(new Category(me, CategoryKind.RECEITA, "Salário", null));

        when(wallets.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());
        when(wallets.findByIdAndUserId(wallet.getId(), me)).thenReturn(Optional.of(wallet));
        when(wallets.findByIdAndUserId(wallet2.getId(), me)).thenReturn(Optional.of(wallet2));
        when(categories.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());
        when(categories.findByIdAndUserId(expense.getId(), me)).thenReturn(Optional.of(expense));
        when(categories.findByIdAndUserId(income.getId(), me)).thenReturn(Optional.of(income));
        when(wallets.findByUserIdOrderByPositionAscNameAsc(me)).thenReturn(List.of(wallet, wallet2));
        when(categories.findByUserIdOrderByNameAsc(me)).thenReturn(List.of(expense, income));
        when(transactions.saveAllAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    private TransactionRequest req(TransactionKind kind, String amount, UUID walletId, UUID target, UUID category) {
        return TransactionRequest.simple(kind, null, LocalDate.of(2026, 11, 10), "Teste", new BigDecimal(amount),
                walletId, target, category, null);
    }

    @Test
    void despesaComCategoriaDeDespesa() {
        var r = service.create(me, req(TransactionKind.DESPESA, "10.555", wallet.getId(), null, expense.getId()), Origin.WEB);
        assertEquals(new BigDecimal("10.56"), r.amount());
        assertEquals("Mercado", r.categoryName());
    }

    @Test
    void despesaComCategoriaDeReceitaEhRecusada() {
        assertThrows(ApiException.class,
                () -> service.create(me, req(TransactionKind.DESPESA, "10", wallet.getId(), null, income.getId()), Origin.WEB));
    }

    @Test
    void valorNegativoSoNoAjuste() {
        assertThrows(ApiException.class,
                () -> service.create(me, req(TransactionKind.DESPESA, "-10", wallet.getId(), null, null), Origin.WEB));
        var r = service.create(me, req(TransactionKind.AJUSTE, "-10", wallet.getId(), null, null), Origin.WEB);
        assertEquals(new BigDecimal("-10.00"), r.amount());
    }

    @Test
    void transferenciaExigeDestinoDiferenteESemCategoria() {
        assertThrows(ApiException.class,
                () -> service.create(me, req(TransactionKind.TRANSFERENCIA, "10", wallet.getId(), null, null), Origin.WEB));
        assertThrows(ApiException.class,
                () -> service.create(me, req(TransactionKind.TRANSFERENCIA, "10", wallet.getId(), wallet.getId(), null), Origin.WEB));
        assertThrows(ApiException.class,
                () -> service.create(me, req(TransactionKind.TRANSFERENCIA, "10", wallet.getId(), wallet2.getId(), expense.getId()), Origin.WEB));
        var r = service.create(me, req(TransactionKind.TRANSFERENCIA, "10", wallet.getId(), wallet2.getId(), null), Origin.WEB);
        assertEquals("VA/VR", r.targetWalletName());
        assertNull(r.categoryId());
    }

    @Test
    void carteiraDeOutroUsuarioNaoExiste() {
        ApiException ex = assertThrows(ApiException.class,
                () -> service.create(other, req(TransactionKind.DESPESA, "10", wallet.getId(), null, null), Origin.WEB));
        assertEquals(404, ex.getStatus().value());
    }

    @Test
    void categoriaDeOutroUsuarioNaoExiste() {
        when(wallets.findByIdAndUserId(eq(wallet.getId()), eq(other))).thenReturn(Optional.of(wallet));
        assertThrows(ApiException.class,
                () -> service.create(other, req(TransactionKind.DESPESA, "10", wallet.getId(), null, expense.getId()), Origin.WEB));
    }

    @Test
    void carteiraArquivadaNaoRecebeLancamentoNovo() {
        wallet.setArchived(true);
        assertThrows(ApiException.class,
                () -> service.create(me, req(TransactionKind.RECEITA, "10", wallet.getId(), null, null), Origin.WEB));
    }

    private static <T> T withId(T entity) throws Exception {
        Field f = entity.getClass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, UUID.randomUUID());
        return entity;
    }
}
