package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByIdAndUserId(UUID id, UUID userId);

    @Query("select count(t) > 0 from Transaction t where t.userId = :userId and (t.walletId = :walletId or t.targetWalletId = :walletId)")
    boolean walletInUse(@Param("userId") UUID userId, @Param("walletId") UUID walletId);

    boolean existsByUserIdAndCategoryId(UUID userId, UUID categoryId);

    boolean existsByUserIdAndCardId(UUID userId, UUID cardId);

    @Query("select count(t) > 0 from Transaction t where t.userId = :userId and t.invoiceId in "
            + "(select i.id from Invoice i where i.creditLineId = :creditLineId)")
    boolean creditLineInUse(@Param("userId") UUID userId, @Param("creditLineId") UUID creditLineId);

    List<Transaction> findByUserIdAndInstallmentGroupOrderByInstallmentNumberAsc(UUID userId, UUID group);

    List<Transaction> findByUserIdAndInvoiceIdOrderByDateAscCreatedAtAsc(UUID userId, UUID invoiceId);

    boolean existsByUserIdAndReversalOfIn(UUID userId, java.util.Collection<UUID> ids);

    boolean existsByUserIdAndRecurrenceId(UUID userId, UUID recurrenceId);

    /** Lançamentos (não cancelados) que realizaram ocorrências de recorrências no período. */
    @Query("select t from Transaction t where t.userId = :userId and t.recurrenceId is not null "
            + "and t.status <> com.controlefin.finance.FinanceEnums.TransactionStatus.CANCELADO "
            + "and t.recurrenceDate between :from and :to")
    List<Transaction> realized(@Param("userId") UUID userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("select count(t) > 0 from Transaction t where t.userId = :userId and t.recurrenceId = :rid "
            + "and t.recurrenceDate = :date and t.status <> com.controlefin.finance.FinanceEnums.TransactionStatus.CANCELADO "
            + "and t.id <> :exceptId")
    boolean occurrenceTaken(@Param("userId") UUID userId, @Param("rid") UUID recurrenceId,
                            @Param("date") LocalDate date, @Param("exceptId") UUID exceptId);

    /** Candidatos a duplicado/vínculo: mesmo tipo, não cancelados, no intervalo de datas. */
    @Query("select t from Transaction t where t.userId = :userId and t.kind = :kind "
            + "and t.status <> com.controlefin.finance.FinanceEnums.TransactionStatus.CANCELADO "
            + "and t.date between :from and :to order by t.date desc")
    List<Transaction> sameKindBetween(@Param("userId") UUID userId, @Param("kind") FinanceEnums.TransactionKind kind,
                                      @Param("from") LocalDate from, @Param("to") LocalDate to);

    long countByUserIdAndStatus(UUID userId, FinanceEnums.TransactionStatus status);

    /**
     * Receitas, despesas (inclusive no crédito) e estornos confirmados no período.
     * Transferências, ajustes e pagamentos de fatura não entram.
     */
    @Query("""
            select t.kind as kind, t.categoryId as categoryId, sum(t.amount) as total
            from Transaction t
            where t.userId = :userId and t.status = com.controlefin.finance.FinanceEnums.TransactionStatus.CONFIRMADO
              and t.kind in (com.controlefin.finance.FinanceEnums.TransactionKind.RECEITA,
                             com.controlefin.finance.FinanceEnums.TransactionKind.DESPESA,
                             com.controlefin.finance.FinanceEnums.TransactionKind.ESTORNO)
              and t.date between :from and :to
            group by t.kind, t.categoryId
            """)
    List<KindCategoryTotal> totalsByCategory(@Param("userId") UUID userId, @Param("from") LocalDate from,
                                             @Param("to") LocalDate to);

    interface KindCategoryTotal {
        FinanceEnums.TransactionKind getKind();
        UUID getCategoryId();
        BigDecimal getTotal();
    }
}
