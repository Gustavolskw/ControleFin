package com.controlefin.finance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InvestmentMovementRepository extends JpaRepository<InvestmentMovement, UUID> {

    /** Em ordem de cálculo: data e, no mesmo dia, ordem de registro. */
    List<InvestmentMovement> findByUserIdOrderByDateAscCreatedAtAsc(UUID userId);

    List<InvestmentMovement> findByUserIdAndInvestmentIdOrderByDateAscCreatedAtAsc(UUID userId, UUID investmentId);

    Optional<InvestmentMovement> findByIdAndUserIdAndInvestmentId(UUID id, UUID userId, UUID investmentId);

    boolean existsByUserIdAndInvestmentId(UUID userId, UUID investmentId);
}
