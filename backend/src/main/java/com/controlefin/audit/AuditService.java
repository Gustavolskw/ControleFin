package com.controlefin.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.controlefin.common.CurrentUser;

/**
 * Registro de atividade: o que foi feito pelo site (WEB) e pela API externa/Claude (API, com o token usado).
 * Grava na mesma transação da operação quando há uma (se ela falhar, o registro também não fica). Protegido por RLS.
 */
@Service
public class AuditService {

    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(UUID id, Instant at, String source, String tokenName, String action, UUID entityId,
                        String details) {
    }

    @Transactional
    public void web(UUID userId, String action, UUID entityId, String details) {
        insert(userId, "WEB", null, action, entityId, details);
    }

    /**
     * Origem decidida pelo principal: com token = API, sem token = WEB. Entra na transação em curso ou abre uma
     * (o RLS exige o usuário informado na transação).
     */
    @Transactional
    public void log(UUID userId, String action, UUID entityId, String details) {
        UUID tokenId = CurrentUser.tokenId();
        insert(userId, tokenId == null ? "WEB" : "API", tokenId, action, entityId, details);
    }

    private void insert(UUID userId, String source, UUID tokenId, String action, UUID entityId, String details) {
        String d = details == null ? null : details.length() > 1000 ? details.substring(0, 1000) : details;
        jdbc.sql("insert into audit_log (id, user_id, source, token_id, action, entity_id, details) "
                        + "values (?, ?, ?, ?, ?, ?, ?)")
                .params(UUID.randomUUID(), userId, source, tokenId, action, entityId, d)
                .update();
    }

    @Transactional(readOnly = true)
    public List<Entry> recent(UUID userId, int limit) {
        return jdbc.sql("select a.id, a.at, a.source, t.name as token_name, a.action, a.entity_id, a.details "
                        + "from audit_log a left join api_tokens t on t.id = a.token_id "
                        + "where a.user_id = ? order by a.at desc limit ?")
                .params(userId, limit)
                .query((rs, i) -> new Entry(rs.getObject("id", UUID.class), rs.getTimestamp("at").toInstant(),
                        rs.getString("source"), rs.getString("token_name"), rs.getString("action"),
                        rs.getObject("entity_id", UUID.class), rs.getString("details")))
                .list();
    }
}
