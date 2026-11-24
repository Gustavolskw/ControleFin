package com.controlefin.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InviteRepository extends JpaRepository<Invite, UUID> {

    /** Marca o convite como usado de forma atômica. Retorna 1 se consumiu, 0 se inválido/expirado/usado/revogado. */
    @Modifying
    @Query("""
            update Invite i set i.usedBy = :username, i.usedAt = :now
            where i.codeHash = :hash and i.usedBy is null and i.revokedAt is null and i.expiresAt > :now
            """)
    int consume(@Param("hash") String hash, @Param("username") String username, @Param("now") Instant now);

    List<Invite> findTop50ByOrderByCreatedAtDesc();
}
