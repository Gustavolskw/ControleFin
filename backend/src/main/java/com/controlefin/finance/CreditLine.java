package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Limite de crédito (ex.: "Nubank"), compartilhado pelos cartões físico e digitais ligados a ele.
 * Define o fechamento e o vencimento das faturas.
 */
@Entity
@Table(name = "credit_lines")
public class CreditLine {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(length = 7)
    private String color;

    @Column(name = "limit_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal limitAmount = BigDecimal.ZERO;

    @Column(name = "closing_day", nullable = false)
    private short closingDay;

    @Column(name = "due_day", nullable = false)
    private short dueDay;

    /** Carteira sugerida para pagar a fatura (opcional). */
    @Column(name = "payment_wallet_id")
    private UUID paymentWalletId;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CreditLine() {
    }

    public CreditLine(UUID userId) {
        this.userId = userId;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public BigDecimal getLimitAmount() { return limitAmount; }
    public void setLimitAmount(BigDecimal limitAmount) { this.limitAmount = limitAmount; }
    public int getClosingDay() { return closingDay; }
    public void setClosingDay(int closingDay) { this.closingDay = (short) closingDay; }
    public int getDueDay() { return dueDay; }
    public void setDueDay(int dueDay) { this.dueDay = (short) dueDay; }
    public UUID getPaymentWalletId() { return paymentWalletId; }
    public void setPaymentWalletId(UUID paymentWalletId) { this.paymentWalletId = paymentWalletId; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
}
