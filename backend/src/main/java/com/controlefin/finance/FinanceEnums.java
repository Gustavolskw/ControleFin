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
     * (crédito na fatura ou entrada na carteira); APLICACAO sai da carteira para um investimento e RESGATE volta
     * dele (nenhum dos dois é despesa/receita; só existem ligados a um movimento de investimento).
     */
    public enum TransactionKind { RECEITA, DESPESA, TRANSFERENCIA, AJUSTE, PAGAMENTO_FATURA, ESTORNO, APLICACAO, RESGATE }

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

    /** Tipo de investimento. Ações, FIIs, ETFs, BDRs e cripto são por quantidade e preço; o resto, por valor. */
    public enum InvestmentType {
        CAIXINHA, CDB, LCI, LCA, TESOURO, POUPANCA, DEBENTURE, CRI_CRA, FUNDO, PREVIDENCIA, ACAO, FII, ETF, BDR, CRIPTO,
        OUTRO;

        public boolean byQuantity() {
            return this == ACAO || this == FII || this == ETF || this == BDR || this == CRIPTO;
        }
    }

    /**
     * Como um investimento por valor rende: CDI/SELIC (rate = % do índice, ex.: 100 = 100% do CDI), PRE (rate = % ao
     * ano), IPCA (rate = spread % ao ano sobre o IPCA), POUPANCA (regra da poupança, sem TR) ou MANUAL (só muda
     * quando você informa o saldo).
     */
    public enum Indexer { CDI, SELIC, PRE, IPCA, POUPANCA, MANUAL }

    /**
     * Movimentos: APORTE/RESGATE (por valor), COMPRA/VENDA (por quantidade), PROVENTO (dividendos, rendimentos de FII,
     * juros) e ATUALIZACAO (por valor: saldo informado; por quantidade: cotação).
     */
    public enum MovementKind { APORTE, RESGATE, COMPRA, VENDA, PROVENTO, ATUALIZACAO }
}
