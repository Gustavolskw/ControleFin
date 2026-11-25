package com.controlefin.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Fatura de um limite de crédito no mês do vencimento. Totais e situação são calculados dos lançamentos. */
@Entity
@Table(name = "invoices")
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "credit_line_id", nullable = false, updatable = false)
    private UUID creditLineId;

    /** 1º dia do mês do vencimento. */
    @Column(name = "reference_month", nullable = false, updatable = false)
    private LocalDate referenceMonth;

    @Column(name = "closing_date", nullable = false)
    private LocalDate closingDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Invoice() {
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getCreditLineId() { return creditLineId; }
    public LocalDate getReferenceMonth() { return referenceMonth; }
    public YearMonth getReference() { return YearMonth.from(referenceMonth); }
    public LocalDate getClosingDate() { return closingDate; }
    public void setClosingDate(LocalDate closingDate) { this.closingDate = closingDate; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
}
