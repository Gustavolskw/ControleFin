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
     * RECEITA e DESPESA mudam o saldo (DESPESA no crédito entra na fatura, não no saldo); TRANSFERENCIA move entre
     * carteiras próprias (não é receita nem despesa); AJUSTE corrige o saldo (valor com sinal);
     * PAGAMENTO_FATURA sai da carteira e abate a fatura (não é despesa); ESTORNO devolve uma compra
     * (crédito na fatura ou entrada na carteira).
     */
    public enum TransactionKind { RECEITA, DESPESA, TRANSFERENCIA, AJUSTE, PAGAMENTO_FATURA, ESTORNO }

    /** Só CONFIRMADO afeta saldo. PENDENTE = aguardando revisão; CANCELADO = desfeito (mantido no histórico). */
    public enum TransactionStatus { CONFIRMADO, PENDENTE, CANCELADO }

    /** De onde veio o registro: site ou API externa (MCP). */
    public enum Origin { WEB, API }

    public enum CardFormat { FISICO, DIGITAL }

    /**
     * CREDITO_DEBITO: usa um limite de crédito (e debita de uma carteira, se tiver uma);
     * SO_DEBITO e VOUCHER_ALIMENTACAO: debitam direto da carteira ligada (ex.: Wiipo VA/VR).
     */
    public enum CardFunction { CREDITO_DEBITO, SO_DEBITO, VOUCHER_ALIMENTACAO }

    /**
     * ABERTA = fatura atual (recebendo compras); FUTURA = meses seguintes (parcelas); FECHADA = fechada a pagar;
     * VENCIDA = passou do vencimento sem pagar tudo; PAGA = fechada e quitada.
     */
    public enum InvoiceStatus { ABERTA, FUTURA, FECHADA, VENCIDA, PAGA }

    public enum Frequency { SEMANAL, MENSAL, ANUAL }

    /**
     * Ocorrência de uma recorrência: REALIZADA (tem lançamento), PULADA, ATRASADA (data já passou sem lançamento)
     * ou PREVISTA (ainda vai acontecer).
     */
    public enum OccurrenceStatus { REALIZADA, PULADA, ATRASADA, PREVISTA }
}
