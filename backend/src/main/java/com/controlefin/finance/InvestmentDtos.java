package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.controlefin.finance.FinanceEnums.Indexer;
import com.controlefin.finance.FinanceEnums.InvestmentType;
import com.controlefin.finance.FinanceEnums.MovementKind;
import com.controlefin.finance.FinanceEnums.Origin;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Investimentos: cadastro, movimentos e carteira consolidada. */
public final class InvestmentDtos {

    private InvestmentDtos() {
    }

    /**
     * Investimento. Por valor (caixinha, CDB, Tesouro…): indexer + rate (CDI/SELIC: % do índice, padrão 100; PRE: %
     * a.a.; IPCA: spread % a.a.; POUPANCA e MANUAL: sem taxa). Por quantidade (ACAO, FII, ETF, BDR, CRIPTO): ticker.
     * accountId = instituição; walletId = carteira padrão para aportes/resgates/proventos.
     */
    public record InvestmentRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull InvestmentType type,
            @Size(max = 20) String ticker,
            UUID accountId,
            UUID walletId,
            Indexer indexer,
            @Digits(integer = 5, fraction = 4) BigDecimal rate,
            LocalDate maturityDate,
            @Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "cor no formato #RRGGBB") String color,
            @Size(max = 1000) String notes,
            Boolean archived) {
    }

    /**
     * Movimento. APORTE/RESGATE/PROVENTO: amount. COMPRA/VENDA: quantity + unitPrice (+ fees); o valor é calculado.
     * ATUALIZACAO: por valor, amount = saldo atual; por quantidade, unitPrice = cotação. walletId (opcional) = de onde
     * sai o dinheiro (aporte/compra) ou para onde vai (resgate/venda/proventos); sem carteira, só registra no
     * investimento (ex.: saldo que já existia). allowDuplicate = grava mesmo havendo movimento igual.
     */
    public record MovementRequest(
            @NotNull MovementKind kind,
            @NotNull LocalDate date,
            @Digits(integer = 15, fraction = 2) BigDecimal amount,
            @Digits(integer = 18, fraction = 10) BigDecimal quantity,
            @Digits(integer = 11, fraction = 8) BigDecimal unitPrice,
            @Digits(integer = 15, fraction = 2) BigDecimal fees,
            UUID walletId,
            @Size(max = 1000) String notes,
            Boolean allowDuplicate) {
    }

    public record MovementResponse(UUID id, UUID investmentId, MovementKind kind, LocalDate date, BigDecimal amount,
                                   BigDecimal quantity, BigDecimal unitPrice, BigDecimal fees, UUID walletId,
                                   String walletName, UUID transactionId, String notes, Origin origin,
                                   Instant createdAt) {
    }

    /**
     * invested: por valor = aportes − resgates; por quantidade = custo da posição (preço médio × quantidade).
     * gain = currentValue − invested (não realizado); realizedGain = lucro das vendas; income = proventos.
     * ratesUntil = até quando o rendimento automático (CDI/IPCA…) foi calculado; lastUpdate = último saldo/cotação
     * informado.
     */
    public record InvestmentResponse(UUID id, String name, InvestmentType type, String ticker, UUID accountId,
                                     String accountName, UUID walletId, String walletName, Indexer indexer,
                                     BigDecimal rate, LocalDate maturityDate, String color, String notes,
                                     boolean archived, boolean byQuantity, BigDecimal invested,
                                     BigDecimal currentValue, BigDecimal gain, BigDecimal gainPct,
                                     BigDecimal quantity, BigDecimal averagePrice, BigDecimal lastPrice,
                                     LocalDate lastPriceDate, BigDecimal realizedGain, BigDecimal income,
                                     LocalDate lastUpdate, LocalDate ratesUntil, int movementCount) {
    }

    public record InvestmentDetail(InvestmentResponse investment, List<MovementResponse> movements) {
    }

    public record TypeTotal(InvestmentType type, BigDecimal value) {
    }

    /** Carteira de investimentos (só os não arquivados entram nos totais). */
    public record Portfolio(BigDecimal invested, BigDecimal currentValue, BigDecimal gain, BigDecimal income,
                            BigDecimal realizedGain, BigDecimal fixedIncome, BigDecimal variableIncome,
                            List<TypeTotal> byType, LocalDate ratesUntil, List<InvestmentResponse> investments) {
    }
}
