-- Fase 1: contas, carteiras (saldos separados dentro da conta), categorias e lançamentos.
--
-- Isolamento por usuário em duas camadas:
--  1. aplicação: toda consulta filtra pelo usuário do token (CurrentUser);
--  2. banco: Row-Level Security. O backend informa o usuário em cada transação (app.user_id); sem ele, nenhuma
--     linha é visível. As FKs compostas (id, user_id) impedem ligar um registro a dados de outro usuário.

CREATE TABLE accounts (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name       VARCHAR(60) NOT NULL,
    type       VARCHAR(20) NOT NULL CHECK (type IN ('CORRENTE', 'POUPANCA', 'PAGAMENTO', 'BENEFICIO',
                                                     'INVESTIMENTO', 'DINHEIRO', 'OUTRO')),
    holder     VARCHAR(2)  NOT NULL CHECK (holder IN ('PF', 'PJ')),
    color      VARCHAR(7),
    archived   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, user_id)
);
CREATE UNIQUE INDEX ux_accounts_user_name ON accounts (user_id, lower(name));

-- Carteira = saldo separado dentro de uma conta (ex.: Wiipo "Livre" e "VA/VR"). Saldo da conta = soma das carteiras.
CREATE TABLE wallets (
    id              UUID PRIMARY KEY,
    user_id         UUID          NOT NULL,
    account_id      UUID          NOT NULL,
    name            VARCHAR(40)   NOT NULL,
    initial_balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    position        INT           NOT NULL DEFAULT 0,
    archived        BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (id, user_id),
    FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX ux_wallets_account_name ON wallets (account_id, lower(name));
CREATE INDEX ix_wallets_user ON wallets (user_id);

CREATE TABLE categories (
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    parent_id  UUID,
    name       VARCHAR(60) NOT NULL,
    kind       VARCHAR(10) NOT NULL CHECK (kind IN ('RECEITA', 'DESPESA')),
    color      VARCHAR(7),
    archived   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, user_id),
    FOREIGN KEY (parent_id, user_id) REFERENCES categories (id, user_id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX ux_categories_name ON categories
    (user_id, kind, COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));

-- Lançamento. amount > 0, exceto AJUSTE (positivo = entrada, negativo = saída).
-- TRANSFERENCIA: sai de wallet_id e entra em target_wallet_id (não é receita nem despesa).
CREATE TABLE transactions (
    id               UUID PRIMARY KEY,
    user_id          UUID           NOT NULL,
    kind             VARCHAR(15)    NOT NULL CHECK (kind IN ('RECEITA', 'DESPESA', 'TRANSFERENCIA', 'AJUSTE')),
    status           VARCHAR(15)    NOT NULL CHECK (status IN ('CONFIRMADO', 'PENDENTE', 'CANCELADO')),
    date             DATE           NOT NULL,
    description      VARCHAR(200)   NOT NULL,
    amount           NUMERIC(19, 2) NOT NULL CHECK (amount <> 0),
    wallet_id        UUID           NOT NULL,
    target_wallet_id UUID,
    category_id      UUID,
    notes            VARCHAR(1000),
    origin           VARCHAR(10)    NOT NULL DEFAULT 'WEB' CHECK (origin IN ('WEB', 'API')),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    FOREIGN KEY (wallet_id, user_id) REFERENCES wallets (id, user_id) ON DELETE CASCADE,
    FOREIGN KEY (target_wallet_id, user_id) REFERENCES wallets (id, user_id) ON DELETE CASCADE,
    FOREIGN KEY (category_id, user_id) REFERENCES categories (id, user_id),
    CHECK ((kind = 'TRANSFERENCIA') = (target_wallet_id IS NOT NULL)),
    CHECK (target_wallet_id IS NULL OR target_wallet_id <> wallet_id),
    CHECK (kind = 'AJUSTE' OR amount > 0),
    CHECK (kind IN ('RECEITA', 'DESPESA') OR category_id IS NULL)
);
CREATE INDEX ix_transactions_user_date ON transactions (user_id, date DESC);
CREATE INDEX ix_transactions_wallet ON transactions (wallet_id);
CREATE INDEX ix_transactions_target_wallet ON transactions (target_wallet_id);
CREATE INDEX ix_transactions_category ON transactions (category_id);

-- Row-Level Security (FORCE: vale também para o dono das tabelas, que é o usuário do backend).
DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['accounts', 'wallets', 'categories', 'transactions'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON %I USING (user_id = NULLIF(current_setting(''app.user_id'', true), '''')::uuid) '
                       'WITH CHECK (user_id = NULLIF(current_setting(''app.user_id'', true), '''')::uuid)',
                       t || '_owner', t);
    END LOOP;
END $$;
