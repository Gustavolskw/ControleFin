package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.controlefin.finance.FinanceEnums.Indexer;
import com.controlefin.finance.FinanceEnums.InvestmentType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Investimento (caixinha, CDB, Tesouro, ação, FII, cripto…). Fica fora das carteiras: o dinheiro entra e sai das
 * contas pelos movimentos (aporte/compra, resgate/venda, proventos).
 */
@Entity
@Table(name = "investments")
public class Investment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 80)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private InvestmentType type;

    @Column(length = 20)
    private String ticker;

    /** Instituição (conta do banco/corretora), só para organizar. */
    @Column(name = "account_id")
    private UUID accountId;

    /** Carteira padrão de onde sai o aporte e para onde volta o resgate/proventos. */
    @Column(name = "wallet_id")
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Indexer indexer;

    @Column(precision = 9, scale = 4)
    private BigDecimal rate;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(length = 7)
    private String color;

    @Column(length = 1000)
    private String notes;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Investment() {
    }

    public Investment(UUID userId) {
        this.userId = userId;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public InvestmentType getType() { return type; }
    public void setType(InvestmentType type) { this.type = type; }
    public String getTicker() { return ticker; }
    public void setTicker(String ticker) { this.ticker = ticker; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getWalletId() { return walletId; }
    public void setWalletId(UUID walletId) { this.walletId = walletId; }
    public Indexer getIndexer() { return indexer; }
    public void setIndexer(Indexer indexer) { this.indexer = indexer; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public void setMaturityDate(LocalDate maturityDate) { this.maturityDate = maturityDate; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }
    public Instant getCreatedAt() { return createdAt; }
}
