package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.controlefin.finance.FinanceEnums.AccountType;
import com.controlefin.finance.FinanceEnums.CardFormat;
import com.controlefin.finance.FinanceEnums.CardFunction;
import com.controlefin.finance.FinanceEnums.Frequency;
import com.controlefin.finance.FinanceEnums.OccurrenceStatus;
import com.controlefin.finance.FinanceEnums.InvoiceStatus;
import com.controlefin.finance.FinanceEnums.CategoryKind;
import com.controlefin.finance.FinanceEnums.Holder;
import com.controlefin.finance.FinanceEnums.Origin;
import com.controlefin.finance.FinanceEnums.TransactionKind;
import com.controlefin.finance.FinanceEnums.TransactionStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class FinanceDtos {

    private FinanceDtos() {
    }

    static final String COLOR = "^#[0-9A-Fa-f]{6}$";

    // ---------------------------------------------------------------- contas e carteiras

    public record WalletRequest(
            @NotBlank @Size(max = 40) String name,
            @NotNull @Digits(integer = 15, fraction = 2) BigDecimal initialBalance) {
    }

    /** Ao criar a conta, informe as carteiras (ex.: Wiipo: "Livre" e "VA/VR"). Sem carteiras, cria "Principal". */
    public record AccountRequest(
            @NotBlank @Size(max = 60) String name,
            @NotNull AccountType type,
            @NotNull Holder holder,
            @Pattern(regexp = COLOR, message = "cor no formato #RRGGBB") String color,
            Boolean archived,
            @Size(max = 10) List<@Valid WalletRequest> wallets) {
    }

    public record WalletUpdateRequest(
            @NotBlank @Size(max = 40) String name,
            @NotNull @Digits(integer = 15, fraction = 2) BigDecimal initialBalance,
            Boolean archived) {
    }

    /** Informe o saldo real da carteira: o sistema lança um AJUSTE com a diferença. */
    public record AdjustRequest(
            @NotNull @Digits(integer = 15, fraction = 2) BigDecimal balance,
            LocalDate date,
            @Size(max = 200) String description) {
    }

    public record WalletResponse(UUID id, UUID accountId, String name, BigDecimal initialBalance, BigDecimal balance,
                                 int position, boolean archived) {
    }

    public record AccountResponse(UUID id, String name, AccountType type, Holder holder, String color,
                                  Boolean archived, BigDecimal balance, List<WalletResponse> wallets) {
    }

    // ---------------------------------------------------------------- categorias

    public record CategoryRequest(
            @NotBlank @Size(max = 60) String name,
            @NotNull CategoryKind kind,
            UUID parentId,
            @Pattern(regexp = COLOR, message = "cor no formato #RRGGBB") String color,
            Boolean archived) {
    }

    public record CategoryResponse(UUID id, String name, CategoryKind kind, UUID parentId, String parentName,
                                   String color, boolean archived) {
    }

    // ---------------------------------------------------------------- lançamentos

    /**
     * Lançamento. Formas de pagamento de uma DESPESA:
     *  - carteira: walletId;
     *  - cartão: cardId + credit (true = crédito, entra na fatura; false = débito/voucher, sai da carteira do cartão).
     * No crédito: installments (parcelas, padrão 1), consumesLimit (padrão true; false = entra na fatura mas não
     * reserva limite) e invoiceId (opcional: força a fatura; senão é escolhida pela data e pelo fechamento).
     * PAGAMENTO_FATURA: walletId (de onde sai) + invoiceId.
     * Moeda estrangeira: originalCurrency + originalAmount + exchangeRate (+ iof); sem amount, o valor em reais é
     * calculado (original × cotação + IOF).
     * Recorrência: recurrenceId + recurrenceDate ligam o lançamento à ocorrência que ele realiza (só ao criar;
     * para mudar o vínculo depois, use /api/transactions/{id}/recurrence).
     */
    public record TransactionRequest(
            @NotNull TransactionKind kind,
            TransactionStatus status,
            @NotNull LocalDate date,
            @NotBlank @Size(max = 200) String description,
            @Digits(integer = 15, fraction = 2) BigDecimal amount,
            UUID walletId,
            UUID targetWalletId,
            UUID categoryId,
            @Size(max = 1000) String notes,
            UUID cardId,
            Boolean credit,
            @Min(1) @Max(72) Integer installments,
            Boolean consumesLimit,
            UUID invoiceId,
            @Pattern(regexp = "^[A-Za-z]{3}$", message = "moeda com 3 letras (ex.: USD)") String originalCurrency,
            @Digits(integer = 15, fraction = 2) BigDecimal originalAmount,
            @Digits(integer = 12, fraction = 6) BigDecimal exchangeRate,
            @Digits(integer = 15, fraction = 2) BigDecimal iof,
            UUID recurrenceId,
            LocalDate recurrenceDate) {

        /** Atalho para lançamentos simples em carteira. */
        public static TransactionRequest simple(TransactionKind kind, TransactionStatus status, LocalDate date,
                                                String description, BigDecimal amount, UUID walletId,
                                                UUID targetWalletId, UUID categoryId, String notes) {
            return new TransactionRequest(kind, status, date, description, amount, walletId, targetWalletId,
                    categoryId, notes, null, null, null, null, null, null, null, null, null, null, null);
        }
    }

    public record TransactionResponse(UUID id, TransactionKind kind, TransactionStatus status, LocalDate date,
                                      String description, BigDecimal amount,
                                      UUID walletId, String walletName, UUID accountId, String accountName,
                                      UUID targetWalletId, String targetWalletName, String targetAccountName,
                                      UUID categoryId, String categoryName, String notes, Origin origin,
                                      Instant createdAt, Instant updatedAt,
                                      UUID cardId, String cardName, UUID creditLineId, String creditLineName,
                                      UUID invoiceId, String invoiceReference, boolean consumesLimit,
                                      UUID installmentGroup, Integer installmentNumber, Integer installmentCount,
                                      String originalCurrency, BigDecimal originalAmount, BigDecimal exchangeRate,
                                      BigDecimal iof, UUID reversalOf, UUID recurrenceId,
                                      LocalDate recurrenceDate) {
    }

    public record TransactionFilter(LocalDate from, LocalDate to, UUID accountId, UUID walletId, UUID categoryId,
                                    TransactionKind kind, TransactionStatus status, String q, UUID cardId,
                                    UUID creditLineId) {
    }

    /** Ligar um lançamento já existente a uma ocorrência de recorrência (ou desligar, com os dois nulos). */
    public record RecurrenceLinkRequest(UUID recurrenceId, LocalDate recurrenceDate) {
    }

    /** Estornar/cancelar uma compra (nunca apaga). date = data do estorno (padrão: hoje). */
    public record ReverseRequest(LocalDate date) {
    }

    // ---------------------------------------------------------------- cartões, limites e faturas

    /** Cartão criado junto com o limite (sempre crédito+débito; carteira opcional para o débito). */
    public record CardInline(
            @NotBlank @Size(max = 60) String name,
            @NotNull CardFormat format,
            UUID walletId) {
    }

    public record CreditLineRequest(
            @NotBlank @Size(max = 60) String name,
            @Pattern(regexp = COLOR, message = "cor no formato #RRGGBB") String color,
            @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 2) BigDecimal limit,
            @NotNull @Min(1) @Max(31) Integer closingDay,
            @NotNull @Min(1) @Max(31) Integer dueDay,
            UUID paymentWalletId,
            Boolean archived,
            @Size(max = 10) List<@Valid CardInline> cards) {
    }

    public record CardRequest(
            @NotBlank @Size(max = 60) String name,
            @NotNull CardFormat format,
            @NotNull CardFunction function,
            UUID creditLineId,
            UUID walletId,
            Boolean archived) {
    }

    public record CardResponse(UUID id, String name, CardFormat format, CardFunction function,
                               UUID creditLineId, String creditLineName, UUID walletId, String walletName,
                               String accountName, boolean archived) {
    }

    /**
     * total = compras − estornos; carriedCredit = crédito vindo da fatura anterior (pago a mais);
     * outstanding = total − pago − crédito anterior (negativo = pago a mais, vai para a próxima).
     */
    public record InvoiceSummary(UUID id, UUID creditLineId, String creditLineName, String reference,
                                 LocalDate closingDate, LocalDate dueDate, InvoiceStatus status,
                                 BigDecimal total, BigDecimal paid, BigDecimal outstanding,
                                 BigDecimal nonConsuming, BigDecimal carriedCredit) {
    }

    public record InvoiceDetail(InvoiceSummary invoice, List<TransactionResponse> items) {
    }

    /**
     * used = parte do limite ocupada (todas as faturas não pagas, inclusive parcelas futuras, sem as compras que
     * não reservam limite). currentInvoice = fatura aberta; dueInvoice = fatura fechada mais antiga a pagar;
     * toPay = soma a pagar das faturas fechadas não pagas + fatura aberta (sem parcelas futuras);
     * reserved = recorrências no crédito que reservam limite e ainda vão cair na fatura aberta
     * (available = limite − used − reserved).
     */
    public record CreditLineResponse(UUID id, String name, String color, BigDecimal limit, int closingDay,
                                     int dueDay, UUID paymentWalletId, String paymentWalletName, boolean archived,
                                     BigDecimal used, BigDecimal available, List<CardResponse> cards,
                                     InvoiceSummary currentInvoice, InvoiceSummary dueInvoice, BigDecimal toPay,
                                     BigDecimal reserved) {
    }

    // ---------------------------------------------------------------- painel

    public record CategoryTotal(UUID categoryId, String name, BigDecimal amount) {
    }

    public record MonthSummary(String month, BigDecimal income, BigDecimal expense, BigDecimal result,
                               List<CategoryTotal> expensesByCategory, List<CategoryTotal> incomeByCategory) {
    }

    /**
     * total = soma de todas as contas e carteiras (PF + PJ + benefícios).
     * projectedEndOfMonth = saldo considerando também os lançamentos confirmados com data até o fim do mês.
     */
    /**
     * invoicesToPay = faturas fechadas não pagas + fatura aberta de cada limite (sem parcelas futuras);
     * projectedEndOfMonth já considera as recorrências do mês (em conta) ainda não lançadas;
     * upcoming = próximas ocorrências (e atrasadas) de recorrências.
     */
    public record DashboardResponse(LocalDate asOf, BigDecimal total, BigDecimal totalPf, BigDecimal totalPj,
                                    BigDecimal projectedEndOfMonth, List<AccountResponse> accounts,
                                    MonthSummary month, long pendingCount, BigDecimal invoicesToPay,
                                    List<CreditLineResponse> creditLines, List<OccurrenceResponse> upcoming) {
    }

    // ---------------------------------------------------------------- recorrências

    /**
     * Recorrência. Pagamento como num lançamento: walletId, ou cardId + credit. dayOfMonth vale para MENSAL e ANUAL
     * (padrão: dia da data de início); ANUAL usa o mês da data de início; SEMANAL repete a cada 7 × intervalCount dias.
     * Moeda estrangeira: originalCurrency + originalAmount; amount = previsão em reais.
     */
    public record RecurrenceRequest(
            @NotNull TransactionKind kind,
            @NotBlank @Size(max = 200) String description,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 15, fraction = 2) BigDecimal amount,
            @NotNull Frequency frequency,
            @Min(1) @Max(12) Integer intervalCount,
            @Min(1) @Max(31) Integer dayOfMonth,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            UUID walletId,
            UUID cardId,
            Boolean credit,
            Boolean consumesLimit,
            UUID categoryId,
            @Pattern(regexp = "^[A-Za-z]{3}$", message = "moeda com 3 letras (ex.: USD)") String originalCurrency,
            @DecimalMin(value = "0.01") @Digits(integer = 15, fraction = 2) BigDecimal originalAmount,
            @Size(max = 1000) String notes,
            Boolean active) {
    }

    public record RecurrenceResponse(UUID id, TransactionKind kind, String description, BigDecimal amount,
                                     Frequency frequency, int intervalCount, Integer dayOfMonth, LocalDate startDate,
                                     LocalDate endDate, UUID walletId, UUID cardId, boolean credit,
                                     boolean consumesLimit, String paidWith, UUID categoryId, String categoryName,
                                     String originalCurrency, BigDecimal originalAmount, String notes, boolean active,
                                     LocalDate nextDate) {
    }

    /**
     * Ocorrência de uma recorrência numa data. transaction = lançamento que a realizou; candidates = lançamentos
     * parecidos ainda sem vínculo (possível duplicado: vincule em vez de lançar de novo).
     */
    public record OccurrenceResponse(UUID recurrenceId, String description, TransactionKind kind, LocalDate date,
                                     BigDecimal expectedAmount, OccurrenceStatus status, UUID transactionId,
                                     BigDecimal actualAmount, String paidWith, boolean credit, int candidates,
                                     String originalCurrency, BigDecimal originalAmount) {
    }

    /** Mês de recorrências: previsto × realizado. pending = previsto ainda não lançado (atrasado + a vencer). */
    public record RecurrenceMonth(String month, BigDecimal expectedExpense, BigDecimal realizedExpense,
                                  BigDecimal pendingExpense, BigDecimal expectedIncome, BigDecimal realizedIncome,
                                  BigDecimal pendingIncome, List<OccurrenceResponse> occurrences,
                                  List<RecurrenceResponse> recurrences) {
    }
}
