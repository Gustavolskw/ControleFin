package com.controlefin.finance;

/** Enumerações do domínio financeiro (mesmos valores dos CHECKs das migrações). */
public final class FinanceEnums {

    private FinanceEnums() {
    }

    public enum AccountType { CORRENTE, POUPANCA, PAGAMENTO, BENEFICIO, INVESTIMENTO, DINHEIRO, OUTRO }

    /** Titularidade: pessoa física ou jurídica. Ambas entram no saldo total. */
    public enum Holder { PF, PJ }

    public enum CategoryKind { RECEITA, DESPESA }

    /**
     * RECEITA e DESPESA mudam o saldo; TRANSFERENCIA move entre carteiras próprias (não é receita nem despesa);
     * AJUSTE corrige o saldo (valor com sinal).
     */
    public enum TransactionKind { RECEITA, DESPESA, TRANSFERENCIA, AJUSTE }

    /** Só CONFIRMADO afeta saldo. PENDENTE = aguardando revisão; CANCELADO = desfeito (mantido no histórico). */
    public enum TransactionStatus { CONFIRMADO, PENDENTE, CANCELADO }

    /** De onde veio o registro: site ou API externa (MCP). */
    public enum Origin { WEB, API }
}
