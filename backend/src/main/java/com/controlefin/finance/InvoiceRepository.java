package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID> {

    Optional<Invoice> findByIdAndUserId(UUID id, UUID userId);

    Optional<Invoice> findByCreditLineIdAndReferenceMonth(UUID creditLineId, LocalDate referenceMonth);

    List<Invoice> findByUserIdOrderByReferenceMonthAsc(UUID userId);

    List<Invoice> findByUserIdAndCreditLineIdOrderByReferenceMonthAsc(UUID userId, UUID creditLineId);

    /** Cria a fatura do mês se ainda não existe (seguro com requisições simultâneas). */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into invoices (id, user_id, credit_line_id, reference_month, closing_date, due_date, created_at)
            values (gen_random_uuid(), :userId, :creditLineId, :ref, :closing, :due, now())
            on conflict (credit_line_id, reference_month) do nothing
            """, nativeQuery = true)
    int createIfMissing(@Param("userId") UUID userId, @Param("creditLineId") UUID creditLineId,
                        @Param("ref") LocalDate ref, @Param("closing") LocalDate closing, @Param("due") LocalDate due);

    /**
     * Totais de cada fatura (só lançamentos CONFIRMADOS):
     * total = compras − estornos; nonConsuming = compras que não reservam limite; paid = pagamentos.
     */
    @Query(value = """
            select i.id as invoiceId,
                   coalesce(sum(case when t.kind = 'DESPESA' then t.amount
                                     when t.kind = 'ESTORNO' then -t.amount else 0 end), 0) as total,
                   coalesce(sum(case when t.kind = 'DESPESA' and not t.consumes_limit then t.amount else 0 end), 0)
                       as nonConsuming,
                   coalesce(sum(case when t.kind = 'PAGAMENTO_FATURA' then t.amount else 0 end), 0) as paid
            from invoices i
            left join transactions t on t.invoice_id = i.id and t.status = 'CONFIRMADO'
            where i.user_id = :userId
            group by i.id
            """, nativeQuery = true)
    List<InvoiceTotals> totals(@Param("userId") UUID userId);

    interface InvoiceTotals {
        UUID getInvoiceId();
        BigDecimal getTotal();
        BigDecimal getNonConsuming();
        BigDecimal getPaid();
    }
}
