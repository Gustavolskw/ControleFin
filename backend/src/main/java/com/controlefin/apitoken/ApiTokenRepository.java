package com.controlefin.apitoken;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ApiTokenRepository extends JpaRepository<ApiToken, UUID> {

    Optional<ApiToken> findByTokenHash(String tokenHash);

    List<ApiToken> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<ApiToken> findByIdAndUserId(UUID id, UUID userId);

    @Query("select count(t) from ApiToken t where t.userId = :userId and t.revokedAt is null "
            + "and t.expiresAt > current_timestamp")
    long countActive(@Param("userId") UUID userId);

    @Modifying
    @Query(value = "update api_tokens set revoked_at = now() where user_id = :userId and revoked_at is null",
            nativeQuery = true)
    int revokeAll(@Param("userId") UUID userId);

    /** Marca o uso no máximo uma vez por minuto (evita escrita a cada chamada). */
    @Transactional
    @Modifying
    @Query(value = "update api_tokens set last_used_at = now() where id = :id "
            + "and (last_used_at is null or last_used_at < now() - interval '1 minute')", nativeQuery = true)
    int touch(@Param("id") UUID id);
}
