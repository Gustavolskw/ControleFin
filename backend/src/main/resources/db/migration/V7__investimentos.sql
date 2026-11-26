-- Investimentos: caixinhas, CDB, LCI/LCA, Tesouro, poupança, fundos, previdência (por valor) e ações, FIIs, ETFs,
-- BDRs e cripto (por quantidade e preço). Ficam fora das carteiras: o painel mostra disponível × investido.
--
-- investments           = o investimento (onde está, como rende).
-- investment_movements  = aporte/resgate (por valor), compra/venda (por quantidade), proventos e atualização de
--                         valor/cotação. Com carteira, o dinheiro sai/entra na conta por um lançamento ligado:
--                         APLICACAO (sai, não é despesa), RESGATE (entra, não é receita) ou RECEITA (proventos).
-- index_rates           = CDI, Selic (% ao dia) e IPCA (% ao mês) do Banco Central (dado público, sem usuário).

CREATE TABLE investments (
    id            UUID PRIMARY KEY,
    user_id       UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name          VARCHAR(80)   NOT NULL,
    type          VARCHAR(15)   NOT NULL CHECK (type IN ('CAIXINHA', 'CDB', 'LCI', 'LCA', 'TESOURO', 'POUPANCA',
                                                         'DEBENTURE', 'CRI_CRA', 'FUNDO', 'PREVIDENCIA', 'ACAO', 'FII',
                                                         'ETF', 'BDR', 'CRIPTO', 'OUTRO')),
    ticker        VARCHAR(20),
    account_id    UUID,
    wallet_id     UUID,
    indexer       VARCHAR(10) CHECK (indexer IN ('CDI', 'SELIC', 'PRE', 'IPCA', 'POUPANCA', 'MANUAL')),
    rate          NUMERIC(9, 4),
    maturity_date DATE,
    color         VARCHAR(7),
    notes         VARCHAR(1000),
    archived      BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (id, user_id),
    FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id),
    FOREIGN KEY (wallet_id, user_id) REFERENCES wallets (id, user_id),
    -- Ações/FIIs/ETFs/BDRs/cripto: por quantidade (sem indexador). O resto: por valor, com indexador.
    CONSTRAINT inv_valuation CHECK (CASE WHEN type IN ('ACAO', 'FII', 'ETF', 'BDR', 'CRIPTO')
                                         THEN indexer IS NULL AND rate IS NULL
                                         ELSE indexer IS NOT NULL END),
    CONSTRAINT inv_rate CHECK (CASE WHEN indexer IN ('CDI', 'SELIC') THEN rate > 0 AND rate <= 500
                                    WHEN indexer IN ('PRE', 'IPCA') THEN rate IS NOT NULL AND rate > -50 AND rate <= 100
                                    ELSE rate IS NULL END)
);
CREATE UNIQUE INDEX ux_investments_name ON investments (user_id, lower(name));
CREATE INDEX ix_investments_account ON investments (account_id);
CREATE INDEX ix_investments_wallet ON investments (wallet_id);

ALTER TABLE transactions
    ADD COLUMN investment_id UUID,
    ADD FOREIGN KEY (investment_id, user_id) REFERENCES investments (id, user_id),
    DROP CONSTRAINT tx_kind,
    ADD CONSTRAINT tx_kind CHECK (kind IN ('RECEITA', 'DESPESA', 'TRANSFERENCIA', 'AJUSTE', 'PAGAMENTO_FATURA',
                                           'ESTORNO', 'APLICACAO', 'RESGATE')),
    -- Aplicação e resgate só existem ligados a um investimento, sempre numa carteira.
    ADD CONSTRAINT tx_investment CHECK (kind NOT IN ('APLICACAO', 'RESGATE')
                                        OR (investment_id IS NOT NULL AND wallet_id IS NOT NULL)),
    ADD CONSTRAINT tx_investment_kinds CHECK (investment_id IS NULL OR kind IN ('APLICACAO', 'RESGATE', 'RECEITA'));
CREATE INDEX ix_transactions_investment ON transactions (investment_id);

CREATE TABLE investment_movements (
    id             UUID PRIMARY KEY,
    user_id        UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    investment_id  UUID           NOT NULL,
    kind           VARCHAR(12)    NOT NULL CHECK (kind IN ('APORTE', 'RESGATE', 'COMPRA', 'VENDA', 'PROVENTO',
                                                           'ATUALIZACAO')),
    date           DATE           NOT NULL,
    amount         NUMERIC(19, 2),
    quantity       NUMERIC(28, 10),
    unit_price     NUMERIC(19, 8),
    fees           NUMERIC(19, 2) NOT NULL DEFAULT 0 CHECK (fees >= 0),
    wallet_id      UUID,
    transaction_id UUID,
    notes          VARCHAR(1000),
    origin         VARCHAR(10)    NOT NULL DEFAULT 'WEB' CHECK (origin IN ('WEB', 'API')),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    FOREIGN KEY (investment_id, user_id) REFERENCES investments (id, user_id) ON DELETE CASCADE,
    FOREIGN KEY (wallet_id, user_id) REFERENCES wallets (id, user_id),
    FOREIGN KEY (transaction_id, user_id) REFERENCES transactions (id, user_id),
    CONSTRAINT mov_values CHECK (CASE
        WHEN kind IN ('APORTE', 'RESGATE', 'PROVENTO') THEN amount > 0 AND quantity IS NULL AND unit_price IS NULL
        WHEN kind IN ('COMPRA', 'VENDA') THEN amount > 0 AND quantity > 0 AND unit_price >= 0
        ELSE quantity IS NULL AND ((amount >= 0 AND unit_price IS NULL) OR (amount IS NULL AND unit_price > 0)) END),
    CONSTRAINT mov_wallet CHECK ((transaction_id IS NULL) = (wallet_id IS NULL)
                                 AND (wallet_id IS NULL OR kind <> 'ATUALIZACAO'))
);
CREATE INDEX ix_investment_movements_investment ON investment_movements (investment_id, date);
CREATE INDEX ix_investment_movements_user ON investment_movements (user_id);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['investments', 'investment_movements'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY %I ON %I USING (user_id = NULLIF(current_setting(''app.user_id'', true), '''')::uuid) '
                       'WITH CHECK (user_id = NULLIF(current_setting(''app.user_id'', true), '''')::uuid)',
                       t || '_owner', t);
    END LOOP;
END $$;

-- Taxas públicas do Banco Central (SGS): CDI (série 12) e Selic (11) em % ao dia útil; IPCA (433) em % ao mês, no
-- dia 1º. As datas do CDI servem também de calendário de dias úteis. Sem RLS: não tem dado de usuário.
CREATE TABLE index_rates (
    index VARCHAR(10)    NOT NULL CHECK (index IN ('CDI', 'SELIC', 'IPCA')),
    date  DATE           NOT NULL,
    value NUMERIC(14, 8) NOT NULL,
    PRIMARY KEY (index, date)
);
