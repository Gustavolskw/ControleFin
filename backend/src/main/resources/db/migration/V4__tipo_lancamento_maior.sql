-- PAGAMENTO_FATURA tem 16 caracteres: amplia o tipo do lançamento.
ALTER TABLE transactions ALTER COLUMN kind TYPE VARCHAR(20);
