-- Fase 3: recorrências (previsto × realizado).
--
-- recurrences       = regra (ex.: "Claude Pro todo dia 5 no Nubank, US$ 20, não reserva limite"). Tem as mesmas opções
--                     de um lançamento: carteira ou cartão (crédito/débito), categoria, moeda estrangeira, observações.
-- recurrence_skips  = ocorrências puladas (ex.: mês sem a cobrança).
-- transactions.recurrence_id + recurrence_date = lançamento que realizou a ocorrência daquela data.
--   Índice único parcial: a mesma ocorrência não pode ser lançada duas vezes (cancelado libera de novo).

CREATE TABLE recurrences (
    id                UUID PRIMARY KEY,
    user_id           UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind              VARCHAR(20)    NOT NULL CHECK (kind IN ('RECEITA', 'DESPESA')),
    description       VARCHAR(200)   NOT NULL,
    amount            NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    frequency         VARCHAR(10)    NOT NULL CHECK (frequency IN ('SEMANAL', 'MENSAL', 'ANUAL')),
    interval_count    SMALLINT       NOT NULL DEFAULT 1 CHECK (interval_count BETWEEN 1 AND 12),
    day_of_month      SMALLINT CHECK (day_of_month BETWEEN 1 AND 31),
    start_date        DATE           NOT NULL,
    end_date          DATE,
    wallet_id         UUID,
    card_id           UUID,
    credit            BOOLEAN        NOT NULL DEFAULT FALSE,
    consumes_limit    BOOLEAN        NOT NULL DEFAULT TRUE,
    category_id       UUID,
    original_currency VARCHAR(3),
    original_amount   NUMERIC(19, 2),
    notes             VARCHAR(1000),
    active            BOOLEAN        NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    UNIQUE (id, user_id),
    FOREIGN KEY (wallet_id, user_id) REFERENCES wallets (id, user_id),
    FOREIGN KEY (card_id, user_id) REFERENCES cards (id, user_id),
    FOREIGN KEY (category_id, user_id) REFERENCES categories (id, user_id),
    CHECK (end_date IS NULL OR end_date >= start_date),
    CHECK ((wallet_id IS NULL) <> (card_id IS NULL)),
    CHECK (NOT credit OR card_id IS NOT NULL),
    CHECK (frequency = 'SEMANAL' OR day_of_month IS NOT NULL),
    CHECK ((original_currency IS NULL) = (original_amount IS NULL)),
    CHECK (original_currency IS NULL OR (original_currency ~ '^[A-Z]{3}$' AND original_currency <> 'BRL'
                                         AND original_amount > 0 AND kind = 'DESPESA'))
);
CREATE INDEX ix_recurrences_user ON recurrences (user_id);
CREATE INDEX ix_recurrences_card ON recurrences (card_id);
CREATE INDEX ix_recurrences_wallet ON recurrences (wallet_id);

CREATE TABLE recurrence_skips (
    recurrence_id UUID        NOT NULL,
    user_id       UUID        NOT NULL,
    date          DATE        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (recurrence_id, date),
    FOREIGN KEY (recurrence_id, user_id) REFERENCES recurrences (id, user_id) ON DELETE CASCADE
);

ALTER TABLE transactions
    ADD COLUMN recurrence_id   UUID,
    ADD COLUMN recurrence_date DATE,
    ADD FOREIGN KEY (recurrence_id, user_id) REFERENCES recurrences (id, user_id),
    ADD CONSTRAINT tx_recurrence CHECK ((recurrence_id IS NULL) = (recurrence_date IS NULL)
                                        AND (recurrence_id IS NULL OR installment_group IS NULL));

CREATE UNIQUE INDEX ux_transactions_recurrence_occurrence ON transactions (recurrence_id, recurrence_date)
    WHERE recurrence_id IS NOT NULL AND status <> 'CANCELADO';

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['recurrences', 'recurrence_skips'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON %I USING (user_id = NULLIF(current_setting(''app.user_id'', true), '''')::uuid) '
                       'WITH CHECK (user_id = NULLIF(current_setting(''app.user_id'', true), '''')::uuid)',
                       t || '_owner', t);
    END LOOP;
END $$;
