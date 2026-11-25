package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.controlefin.finance.FinanceEnums.Frequency;
import com.controlefin.finance.FinanceEnums.TransactionKind;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Recorrência: receita ou despesa que se repete (assinatura, aluguel, salário). Tem as mesmas opções de um
 * lançamento. O valor é o previsto (em reais); o realizado é o do lançamento ligado a cada ocorrência.
 */
@Entity
@Table(name = "recurrences")
public class Recurrence {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionKind kind;

    @Column(nullable = false, length = 200)
    private String description;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Frequency frequency;

    @Column(name = "interval_count", nullable = false)
    private short intervalCount = 1;

    @Column(name = "day_of_month")
    private Short dayOfMonth;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "wallet_id")
    private UUID walletId;

    @Column(name = "card_id")
    private UUID cardId;

    @Column(nullable = false)
    private boolean credit;

    @Column(name = "consumes_limit", nullable = false)
    private boolean consumesLimit = true;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "original_currency", length = 3)
    private String originalCurrency;

    @Column(name = "original_amount", precision = 19, scale = 2)
    private BigDecimal originalAmount;

    @Column(length = 1000)
    private String notes;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Recurrence() {
    }

    public Recurrence(UUID userId) {
        this.userId = userId;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public TransactionKind getKind() { return kind; }
    public void setKind(TransactionKind kind) { this.kind = kind; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Frequency getFrequency() { return frequency; }
    public void setFrequency(Frequency frequency) { this.frequency = frequency; }
    public int getIntervalCount() { return intervalCount; }
    public void setIntervalCount(int intervalCount) { this.intervalCount = (short) intervalCount; }
    public Integer getDayOfMonth() { return dayOfMonth == null ? null : dayOfMonth.intValue(); }
    public void setDayOfMonth(Integer day) { this.dayOfMonth = day == null ? null : day.shortValue(); }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public UUID getWalletId() { return walletId; }
    public void setWalletId(UUID walletId) { this.walletId = walletId; }
    public UUID getCardId() { return cardId; }
    public void setCardId(UUID cardId) { this.cardId = cardId; }
    public boolean isCredit() { return credit; }
    public void setCredit(boolean credit) { this.credit = credit; }
    public boolean isConsumesLimit() { return consumesLimit; }
    public void setConsumesLimit(boolean consumesLimit) { this.consumesLimit = consumesLimit; }
    public UUID getCategoryId() { return categoryId; }
    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
    public String getOriginalCurrency() { return originalCurrency; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public void setForeign(String currency, BigDecimal amount) {
        this.originalCurrency = currency;
        this.originalAmount = amount;
    }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
