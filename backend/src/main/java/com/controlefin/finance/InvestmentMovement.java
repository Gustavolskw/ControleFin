package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.controlefin.finance.FinanceEnums.MovementKind;
import com.controlefin.finance.FinanceEnums.Origin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Movimento de um investimento. amount: valor em reais (compra = quantidade × preço + taxas; venda = quantidade ×
 * preço − taxas; atualização por valor = saldo informado). Com carteira, há um lançamento ligado (transactionId).
 */
@Entity
@Table(name = "investment_movements")
public class InvestmentMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "investment_id", nullable = false, updatable = false)
    private UUID investmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private MovementKind kind;

    @Column(nullable = false)
    private LocalDate date;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(precision = 28, scale = 10)
    private BigDecimal quantity;

    @Column(name = "unit_price", precision = 19, scale = 8)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal fees = BigDecimal.ZERO;

    @Column(name = "wallet_id")
    private UUID walletId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private Origin origin = Origin.WEB;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected InvestmentMovement() {
    }

    public InvestmentMovement(UUID userId, UUID investmentId, Origin origin) {
        this.userId = userId;
        this.investmentId = investmentId;
        this.origin = origin;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getInvestmentId() { return investmentId; }
    public MovementKind getKind() { return kind; }
    public void setKind(MovementKind kind) { this.kind = kind; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getFees() { return fees; }
    public void setFees(BigDecimal fees) { this.fees = fees; }
    public UUID getWalletId() { return walletId; }
    public void setWalletId(UUID walletId) { this.walletId = walletId; }
    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Origin getOrigin() { return origin; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void touch() { this.updatedAt = Instant.now(); }
}
