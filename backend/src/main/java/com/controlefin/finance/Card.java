package com.controlefin.finance;

import java.time.Instant;
import java.util.UUID;

import com.controlefin.finance.FinanceEnums.CardFormat;
import com.controlefin.finance.FinanceEnums.CardFunction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Cartão físico ou digital. Não guarda número do cartão. */
@Entity
@Table(name = "cards")
public class Card {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private CardFormat format;

    @Enumerated(EnumType.STRING)
    @Column(name = "function", nullable = false, length = 25)
    private CardFunction function;

    @Column(name = "credit_line_id")
    private UUID creditLineId;

    /** Carteira debitada nas compras no débito/voucher. */
    @Column(name = "wallet_id")
    private UUID walletId;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Card() {
    }

    public Card(UUID userId) {
        this.userId = userId;
    }

    public boolean hasCredit() { return function == CardFunction.CREDITO_DEBITO && creditLineId != null; }
    public boolean hasDebit() { return walletId != null; }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public CardFormat getFormat() { return format; }
    public void setFormat(CardFormat format) { this.format = format; }
    public CardFunction getFunction() { return function; }
    public void setFunction(CardFunction function) { this.function = function; }
    public UUID getCreditLineId() { return creditLineId; }
    public void setCreditLineId(UUID creditLineId) { this.creditLineId = creditLineId; }
    public UUID getWalletId() { return walletId; }
    public void setWalletId(UUID walletId) { this.walletId = walletId; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
}
