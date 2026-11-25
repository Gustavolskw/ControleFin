package com.controlefin.finance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecurrenceRepository extends JpaRepository<Recurrence, UUID> {

    List<Recurrence> findByUserIdOrderByDescriptionAsc(UUID userId);

    Optional<Recurrence> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndCardId(UUID userId, UUID cardId);

    boolean existsByUserIdAndWalletId(UUID userId, UUID walletId);

    // ---------------------------------------------------------------- ocorrências puladas

    @Query(value = "select recurrence_id as recurrenceId, date as date from recurrence_skips where user_id = :userId",
            nativeQuery = true)
    List<Skip> skips(@Param("userId") UUID userId);

    interface Skip {
        UUID getRecurrenceId();
        LocalDate getDate();
    }

    @Modifying(flushAutomatically = true)
    @Query(value = "insert into recurrence_skips (recurrence_id, user_id, date) values (:id, :userId, :date) "
            + "on conflict do nothing", nativeQuery = true)
    int skip(@Param("id") UUID recurrenceId, @Param("userId") UUID userId, @Param("date") LocalDate date);

    @Modifying(flushAutomatically = true)
    @Query(value = "delete from recurrence_skips where recurrence_id = :id and date = :date", nativeQuery = true)
    int unskip(@Param("id") UUID recurrenceId, @Param("date") LocalDate date);
}
