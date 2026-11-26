package com.controlefin.finance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestmentRepository extends JpaRepository<Investment, UUID> {

    List<Investment> findByUserIdOrderByNameAsc(UUID userId);

    Optional<Investment> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndNameIgnoreCase(UUID userId, String name);

    boolean existsByUserIdAndNameIgnoreCaseAndIdNot(UUID userId, String name, UUID id);

    boolean existsByUserIdAndWalletId(UUID userId, UUID walletId);

    boolean existsByUserIdAndAccountId(UUID userId, UUID accountId);
}
