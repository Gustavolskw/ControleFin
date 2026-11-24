package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    List<Wallet> findByUserIdOrderByPositionAscNameAsc(UUID userId);

    List<Wallet> findByUserIdAndAccountIdOrderByPositionAscNameAsc(UUID userId, UUID accountId);

    Optional<Wallet> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByAccountIdAndNameIgnoreCase(UUID accountId, String name);

    boolean existsByAccountIdAndNameIgnoreCaseAndIdNot(UUID accountId, String name, UUID id);

    /** Saldo de cada carteira até a data: inicial + lançamentos CONFIRMADOS (entradas, saídas e transferências). */
    @Query(value = """
            select w.id as walletId,
                   w.initial_balance
                   + coalesce((select sum(case when t.kind in ('RECEITA', 'AJUSTE') then t.amount else -t.amount end)
                               from transactions t
                               where t.wallet_id = w.id and t.status = 'CONFIRMADO' and t.date <= :asOf), 0)
                   + coalesce((select sum(t.amount)
                               from transactions t
                               where t.target_wallet_id = w.id and t.kind = 'TRANSFERENCIA'
                                 and t.status = 'CONFIRMADO' and t.date <= :asOf), 0) as balance
            from wallets w
            where w.user_id = :userId
            """, nativeQuery = true)
    List<WalletBalance> balances(@Param("userId") UUID userId, @Param("asOf") LocalDate asOf);

    interface WalletBalance {
        UUID getWalletId();
        BigDecimal getBalance();
    }
}
