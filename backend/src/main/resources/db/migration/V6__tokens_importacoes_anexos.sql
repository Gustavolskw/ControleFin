-- Fase 4: tokens pessoais de API (Claude/MCP), lotes de importação, anexos criptografados e registro de atividade.

-- Token pessoal: guardado só como hash SHA-256 (o valor aparece uma única vez, ao criar).
-- Sem RLS de propósito (como a tabela users): o token é procurado pelo hash ANTES de se saber o usuário.
-- Todo acesso pelo site filtra por user_id na aplicação.
CREATE TABLE api_tokens (
    id           UUID PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name         VARCHAR(60) NOT NULL,
    prefix       VARCHAR(12) NOT NULL,
    token_hash   VARCHAR(64) NOT NULL UNIQUE,
    can_write    BOOLEAN     NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_api_tokens_user ON api_tokens (user_id);

-- Lote enviado pelo Claude. mode: ANALISAR (entra pendente), CONFIRMADO (padrão), REMOVER (cancela/estorna).
CREATE TABLE import_batches (
    id              UUID PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_id        UUID         REFERENCES api_tokens (id) ON DELETE SET NULL,
    mode            VARCHAR(12)  NOT NULL CHECK (mode IN ('ANALISAR', 'CONFIRMADO', 'REMOVER')),
    idempotency_key VARCHAR(100),
    note            VARCHAR(500),
    status          VARCHAR(12)  NOT NULL DEFAULT 'PROCESSADO' CHECK (status IN ('PROCESSADO', 'DESFEITO')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    undone_at       TIMESTAMPTZ,
    UNIQUE (id, user_id)
);
CREATE UNIQUE INDEX ux_import_batches_idempotency ON import_batches (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_import_batches_user ON import_batches (user_id, created_at DESC);

-- Resultado de cada item do lote.
CREATE TABLE import_items (
    id             UUID PRIMARY KEY,
    user_id        UUID         NOT NULL,
    batch_id       UUID         NOT NULL,
    position       INT          NOT NULL,
    status         VARCHAR(12)  NOT NULL CHECK (status IN ('CRIADO', 'PENDENTE', 'DUPLICADO', 'ERRO', 'REMOVIDO',
                                                           'ESTORNADO', 'DESFEITO')),
    transaction_id UUID,
    date           DATE,
    description    VARCHAR(200),
    amount         NUMERIC(19, 2),
    message        VARCHAR(1000),
    FOREIGN KEY (batch_id, user_id) REFERENCES import_batches (id, user_id) ON DELETE CASCADE,
    FOREIGN KEY (transaction_id, user_id) REFERENCES transactions (id, user_id) ON DELETE SET NULL (transaction_id)
);
CREATE INDEX ix_import_items_batch ON import_items (batch_id, position);
CREATE INDEX ix_import_items_transaction ON import_items (transaction_id);

ALTER TABLE transactions
    ADD COLUMN import_batch_id UUID,
    ADD FOREIGN KEY (import_batch_id, user_id) REFERENCES import_batches (id, user_id) ON DELETE SET NULL (import_batch_id);
CREATE INDEX ix_transactions_import_batch ON transactions (import_batch_id);

-- Anexos (prints, PDFs, CSV, Excel): sempre guardados, criptografados (AES-GCM) num volume próprio.
-- O mesmo arquivo (sha256) do mesmo usuário é guardado uma vez só.
CREATE TABLE attachments (
    id            UUID PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    original_name VARCHAR(200) NOT NULL,
    mime_type     VARCHAR(100) NOT NULL,
    size_bytes    BIGINT       NOT NULL CHECK (size_bytes > 0),
    sha256        VARCHAR(64)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (id, user_id),
    UNIQUE (user_id, sha256)
);

CREATE TABLE transaction_attachments (
    transaction_id UUID        NOT NULL,
    attachment_id  UUID        NOT NULL,
    user_id        UUID        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (transaction_id, attachment_id),
    FOREIGN KEY (transaction_id, user_id) REFERENCES transactions (id, user_id) ON DELETE CASCADE,
    FOREIGN KEY (attachment_id, user_id) REFERENCES attachments (id, user_id) ON DELETE CASCADE
);
CREATE INDEX ix_transaction_attachments_attachment ON transaction_attachments (attachment_id);

CREATE TABLE import_batch_attachments (
    batch_id      UUID NOT NULL,
    attachment_id UUID NOT NULL,
    user_id       UUID NOT NULL,
    position      INT  NOT NULL,
    PRIMARY KEY (batch_id, attachment_id),
    FOREIGN KEY (batch_id, user_id) REFERENCES import_batches (id, user_id) ON DELETE CASCADE,
    FOREIGN KEY (attachment_id, user_id) REFERENCES attachments (id, user_id) ON DELETE CASCADE
);

-- Registro de atividade (o que foi feito pelo site e pela API/Claude).
CREATE TABLE audit_log (
    id        UUID PRIMARY KEY,
    user_id   UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    source    VARCHAR(5)    NOT NULL CHECK (source IN ('WEB', 'API')),
    token_id  UUID          REFERENCES api_tokens (id) ON DELETE SET NULL,
    action    VARCHAR(40)   NOT NULL,
    entity_id UUID,
    details   VARCHAR(1000)
);
CREATE INDEX ix_audit_log_user ON audit_log (user_id, at DESC);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['import_batches', 'import_items', 'attachments', 'transaction_attachments',
                             'import_batch_attachments', 'audit_log'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON %I USING (user_id = NULLIF(current_setting(''app.user_id'', true), '''')::uuid) '
                       'WITH CHECK (user_id = NULLIF(current_setting(''app.user_id'', true), '''')::uuid)',
                       t || '_owner', t);
    END LOOP;
END $$;
