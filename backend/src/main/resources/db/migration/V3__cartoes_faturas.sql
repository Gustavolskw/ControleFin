-- Fase 2: cartões, limites de crédito compartilhados, faturas, parcelamento, moeda estrangeira e estorno.
--
-- Modelo:
--  credit_lines  = limite de crédito (ex.: "Nubank"), compartilhado pelos cartões físico e digitais dele;
--                  define o dia de fechamento e de vencimento das faturas.
--  cards         = cartão (nome, físico/digital, função). Sem número de cartão.
--                  CREDITO_DEBITO usa um limite (e, se tiver carteira, também débito);
--                  SO_DEBITO e VOUCHER_ALIMENTACAO debitam direto de uma carteira (ex.: Wiipo VA/VR).
--  invoices      = fatura de um limite em um mês de vencimento (reference_month = 1º dia do mês do vencimento).
--  transactions  = compra no crédito: DESPESA com invoice_id e sem carteira (não mexe no saldo, entra na fatura);
--                  PAGAMENTO_FATURA: sai da carteira e abate a fatura (não é despesa);
--                  ESTORNO: crédito na fatura (ou entrada na carteira), ligado à compra original.

CREATE TABLE credit_lines (
    id                UUID PRIMARY KEY,
    user_id           UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name              VARCHAR(60)    NOT NULL,
    color             VARCHAR(7),
    limit_amount      NUMERIC(19, 2) NOT NULL CHECK (limit_amount >= 0),
    closing_day       SMALLINT       NOT NULL CHECK (closing_day BETWEEN 1 AND 31),
    due_day           SMALLINT       NOT NULL CHECK (due_day BETWEEN 1 AND 31),
    payment_wallet_id UUID,
    archived          BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (id, user_id),
    CHECK (closing_day <> due_day),
    FOREIGN KEY (payment_wallet_id, user_id) REFERENCES wallets (id, user_id) ON DELETE SET NULL (payment_wallet_id)
);
CREATE UNIQUE INDEX ux_credit_lines_user_name ON credit_lines (user_id, lower(name));

CREATE TABLE cards (
    id             UUID PRIMARY KEY,
    user_id        UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name           VARCHAR(60) NOT NULL,
    format         VARCHAR(10) NOT NULL CHECK (format IN ('FISICO', 'DIGITAL')),
    function       VARCHAR(25) NOT NULL CHECK (function IN ('CREDITO_DEBITO', 'SO_DEBITO', 'VOUCHER_ALIMENTACAO')),
    credit_line_id UUID,
    wallet_id      UUID,
    archived       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, user_id),
    FOREIGN KEY (credit_line_id, user_id) REFERENCES credit_lines (id, user_id) ON DELETE CASCADE,
    FOREIGN KEY (wallet_id, user_id) REFERENCES wallets (id, user_id),
    CONSTRAINT cards_function_links CHECK (
        (function = 'CREDITO_DEBITO' AND credit_line_id IS NOT NULL)
        OR (function IN ('SO_DEBITO', 'VOUCHER_ALIMENTACAO') AND credit_line_id IS NULL AND wallet_id IS NOT NULL))
);
CREATE UNIQUE INDEX ux_cards_user_name ON cards (user_id, lower(name));
CREATE INDEX ix_cards_credit_line ON cards (credit_line_id);
CREATE INDEX ix_cards_wallet ON cards (wallet_id);

CREATE TABLE invoices (
    id              UUID PRIMARY KEY,
    user_id         UUID        NOT NULL,
    credit_line_id  UUID        NOT NULL,
    reference_month DATE        NOT NULL CHECK (EXTRACT(DAY FROM reference_month) = 1),
    closing_date    DATE        NOT NULL,
    due_date        DATE        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, user_id),
    UNIQUE (credit_line_id, reference_month),
    CHECK (closing_date < due_date),
    FOREIGN KEY (credit_line_id, user_id) REFERENCES credit_lines (id, user_id) ON DELETE CASCADE
);
CREATE INDEX ix_invoices_user ON invoices (user_id);

-- ---------------------------------------------------------------- lançamentos

-- FK composta para reversal_of exige UNIQUE (id, user_id).
ALTER TABLE transactions ADD CONSTRAINT transactions_id_user UNIQUE (id, user_id);

ALTER TABLE transactions
    ALTER COLUMN wallet_id DROP NOT NULL,
    ADD COLUMN card_id            UUID,
    ADD COLUMN invoice_id         UUID,
    ADD COLUMN consumes_limit     BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN installment_group  UUID,
    ADD COLUMN installment_number SMALLINT,
    ADD COLUMN installment_count  SMALLINT,
    ADD COLUMN original_currency  VARCHAR(3),
    ADD COLUMN original_amount    NUMERIC(19, 2),
    ADD COLUMN exchange_rate      NUMERIC(19, 6),
    ADD COLUMN iof                NUMERIC(19, 2),
    ADD COLUMN reversal_of        UUID,
    ADD FOREIGN KEY (card_id, user_id) REFERENCES cards (id, user_id),
    ADD FOREIGN KEY (invoice_id, user_id) REFERENCES invoices (id, user_id),
    ADD FOREIGN KEY (reversal_of, user_id) REFERENCES transactions (id, user_id) ON DELETE SET NULL (reversal_of);

-- Recria todas as regras (CHECK) com nomes estáveis, já com os tipos novos.
DO $$
DECLARE c RECORD;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint WHERE conrelid = 'transactions'::regclass AND contype = 'c' LOOP
        EXECUTE format('ALTER TABLE transactions DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE transactions
    ADD CONSTRAINT tx_kind CHECK (kind IN ('RECEITA', 'DESPESA', 'TRANSFERENCIA', 'AJUSTE', 'PAGAMENTO_FATURA', 'ESTORNO')),
    ADD CONSTRAINT tx_status CHECK (status IN ('CONFIRMADO', 'PENDENTE', 'CANCELADO')),
    ADD CONSTRAINT tx_origin CHECK (origin IN ('WEB', 'API')),
    ADD CONSTRAINT tx_amount CHECK (amount <> 0 AND (kind = 'AJUSTE' OR amount > 0)),
    ADD CONSTRAINT tx_transfer CHECK ((kind = 'TRANSFERENCIA') = (target_wallet_id IS NOT NULL)
                                      AND (target_wallet_id IS NULL OR target_wallet_id <> wallet_id)),
    ADD CONSTRAINT tx_category CHECK (kind IN ('RECEITA', 'DESPESA', 'ESTORNO') OR category_id IS NULL),
    -- Toda movimentação está numa carteira ou numa fatura; só o pagamento de fatura tem as duas.
    ADD CONSTRAINT tx_source CHECK (CASE WHEN kind = 'PAGAMENTO_FATURA' THEN wallet_id IS NOT NULL AND invoice_id IS NOT NULL
                                         ELSE (wallet_id IS NULL) <> (invoice_id IS NULL) END),
    ADD CONSTRAINT tx_invoice_kinds CHECK (invoice_id IS NULL OR kind IN ('DESPESA', 'ESTORNO', 'PAGAMENTO_FATURA')),
    ADD CONSTRAINT tx_installments CHECK (
        (installment_group IS NULL AND installment_number IS NULL AND installment_count IS NULL)
        OR (installment_group IS NOT NULL AND installment_count BETWEEN 2 AND 72
            AND installment_number BETWEEN 1 AND installment_count AND invoice_id IS NOT NULL)),
    ADD CONSTRAINT tx_currency CHECK (
        (original_currency IS NULL AND original_amount IS NULL AND exchange_rate IS NULL AND iof IS NULL)
        OR (original_currency ~ '^[A-Z]{3}$' AND original_currency <> 'BRL' AND original_amount > 0
            AND exchange_rate > 0 AND (iof IS NULL OR iof >= 0) AND kind IN ('DESPESA', 'ESTORNO'))),
    ADD CONSTRAINT tx_reversal CHECK (reversal_of IS NULL OR kind = 'ESTORNO');

CREATE INDEX ix_transactions_card ON transactions (card_id);
CREATE INDEX ix_transactions_invoice ON transactions (invoice_id);
CREATE INDEX ix_transactions_installment_group ON transactions (installment_group);
CREATE INDEX ix_transactions_reversal ON transactions (reversal_of);

-- ---------------------------------------------------------------- Row-Level Security nas tabelas novas

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['credit_lines', 'cards', 'invoices'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON %I USING (user_id = NULLIF(current_setting(''app.user_id'', true), '''')::uuid) '
                       'WITH CHECK (user_id = NULLIF(current_setting(''app.user_id'', true), '''')::uuid)',
                       t || '_owner', t);
    END LOOP;
END $$;
