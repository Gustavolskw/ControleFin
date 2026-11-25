package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.controlefin.finance.FinanceEnums.Origin;
import com.controlefin.finance.FinanceEnums.TransactionKind;
import com.controlefin.finance.FinanceEnums.TransactionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Lançamento: em carteira (receita, despesa, transferência, ajuste, estorno) ou em fatura de cartão de crédito
 * (compra, estorno). O pagamento de fatura tem os dois: sai da carteira e abate a fatura.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private TransactionStatus status = TransactionStatus.CONFIRMADO;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "wallet_id")
    private UUID walletId;

    @Column(name = "target_wallet_id")
    private UUID targetWalletId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(length = 1000)
    private String notes;

    @Column(name = "card_id")
    private UUID cardId;

    @Column(name = "invoice_id")
    private UUID invoiceId;

    /** Compra no crédito que não reserva limite (ex.: assinatura que entra na fatura mas deixa o crédito livre). */
    @Column(name = "consumes_limit", nullable = false)
    private boolean consumesLimit = true;

    @Column(name = "installment_group")
    private UUID installmentGroup;

    @Column(name = "installment_number")
    private Short installmentNumber;

    @Column(name = "installment_count")
    private Short installmentCount;

    @Column(name = "original_currency", length = 3)
    private String originalCurrency;

    @Column(name = "original_amount", precision = 19, scale = 2)
    private BigDecimal originalAmount;

    @Column(name = "exchange_rate", precision = 19, scale = 6)
    private BigDecimal exchangeRate;

    @Column(precision = 19, scale = 2)
    private BigDecimal iof;

    /** Ocorrência de recorrência que este lançamento realizou. */
    @Column(name = "recurrence_id")
    private UUID recurrenceId;

    @Column(name = "recurrence_date")
    private LocalDate recurrenceDate;

    /** Estorno: compra original. */
    @Column(name = "reversal_of")
    private UUID reversalOf;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private Origin origin = Origin.WEB;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Transaction() {
    }

    public Transaction(UUID userId, Origin origin) {
        this.userId = userId;
        this.origin = origin;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public TransactionKind getKind() { return kind; }
    public void setKind(TransactionKind kind) { this.kind = kind; }
    public TransactionStatus getStatus() { return status; }
    public void setStatus(TransactionStatus status) { this.status = status; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public UUID getWalletId() { return walletId; }
    public void setWalletId(UUID walletId) { this.walletId = walletId; }
    public UUID getTargetWalletId() { return targetWalletId; }
    public void setTargetWalletId(UUID targetWalletId) { this.targetWalletId = targetWalletId; }
    public UUID getCategoryId() { return categoryId; }
    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public UUID getCardId() { return cardId; }
    public void setCardId(UUID cardId) { this.cardId = cardId; }
    public UUID getInvoiceId() { return invoiceId; }
    public void setInvoiceId(UUID invoiceId) { this.invoiceId = invoiceId; }
    public boolean isConsumesLimit() { return consumesLimit; }
    public void setConsumesLimit(boolean consumesLimit) { this.consumesLimit = consumesLimit; }
    public UUID getInstallmentGroup() { return installmentGroup; }
    public Integer getInstallmentNumber() { return installmentNumber == null ? null : installmentNumber.intValue(); }
    public Integer getInstallmentCount() { return installmentCount == null ? null : installmentCount.intValue(); }
    public void setInstallment(UUID group, Integer number, Integer count) {
        this.installmentGroup = group;
        this.installmentNumber = number == null ? null : number.shortValue();
        this.installmentCount = count == null ? null : count.shortValue();
    }
    public String getOriginalCurrency() { return originalCurrency; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public BigDecimal getExchangeRate() { return exchangeRate; }
    public BigDecimal getIof() { return iof; }
    public void setForeign(String currency, BigDecimal originalAmount, BigDecimal exchangeRate, BigDecimal iof) {
        this.originalCurrency = currency;
        this.originalAmount = originalAmount;
        this.exchangeRate = exchangeRate;
        this.iof = iof;
    }
    public UUID getRecurrenceId() { return recurrenceId; }
    public LocalDate getRecurrenceDate() { return recurrenceDate; }
    public void setRecurrence(UUID recurrenceId, LocalDate recurrenceDate) {
        this.recurrenceId = recurrenceId;
        this.recurrenceDate = recurrenceId == null ? null : recurrenceDate;
    }
    public UUID getReversalOf() { return reversalOf; }
    public void setReversalOf(UUID reversalOf) { this.reversalOf = reversalOf; }
    public boolean isCredit() { return invoiceId != null && kind != FinanceEnums.TransactionKind.PAGAMENTO_FATURA; }
    public Origin getOrigin() { return origin; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = Instant.now(); }
}
