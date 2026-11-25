package com.controlefin.finance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditLineRepository extends JpaRepository<CreditLine, UUID> {

    List<CreditLine> findByUserIdOrderByNameAsc(UUID userId);

    Optional<CreditLine> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndNameIgnoreCase(UUID userId, String name);

    boolean existsByUserIdAndNameIgnoreCaseAndIdNot(UUID userId, String name, UUID id);
}
