package com.controlefin.finance;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.controlefin.common.ApiException;
import com.controlefin.finance.FinanceEnums.Indexer;
import com.controlefin.finance.FinanceEnums.InvestmentType;

/**
 * Valor atual de um investimento a partir dos movimentos (sem acesso a banco, para ser testável).
 *
 * <p><b>Por valor</b> (caixinha, CDB, Tesouro…): o saldo rende entre um movimento e outro pelo indexador, nos dias úteis
 * do calendário do CDI:
 * <ul>
 *   <li>CDI/SELIC: fator diário 1 + taxa do dia × rate%;</li>
 *   <li>PRE: (1 + rate% a.a.)^(dias úteis/252);</li>
 *   <li>IPCA: IPCA do mês pró-rata por dia corrido (mês sem IPCA publicado usa o último) + spread rate% a.a. por dia útil;</li>
 *   <li>POUPANCA: 0,5% a.m. com Selic acima de 8,5% a.a., senão 70% da Selic (sem TR), pró-rata por dia corrido;</li>
 *   <li>MANUAL: não rende sozinho.</li>
 * </ul>
 * APORTE soma, RESGATE subtrai e ATUALIZACAO troca o saldo pelo valor informado (ex.: o que o app do banco mostra);
 * depois dela o rendimento continua a partir desse valor. Valores brutos (sem IR). O rendimento vai até o último dia
 * útil com CDI publicado.
 *
 * <p><b>Por quantidade</b> (ações, FIIs, ETFs, BDRs, cripto): custo médio ponderado (compras com taxas); a venda
 * realiza lucro/prejuízo pelo preço médio; o valor atual usa a última cotação (atualização, compra ou venda).
 */
final class InvestmentCalculator {

    private static final MathContext MC = MathContext.DECIMAL64;

    private InvestmentCalculator() {
    }

    /**
     * @param invested     por valor: aportes − resgates; por quantidade: custo da posição atual
     * @param ratesUntil   até quando o rendimento automático foi calculado (null = sem rendimento automático)
     * @param lastUpdate   data da última atualização informada (saldo ou cotação)
     */
    record Valuation(BigDecimal invested, BigDecimal currentValue, BigDecimal quantity, BigDecimal averagePrice,
                     BigDecimal lastPrice, LocalDate lastPriceDate, BigDecimal realizedGain, BigDecimal income,
                     LocalDate lastUpdate, LocalDate ratesUntil) {
    }

    static Valuation value(InvestmentType type, Indexer indexer, BigDecimal rate, List<InvestmentMovement> movements,
                           IndexRates rates, LocalDate asOf) {
        return type.byQuantity() ? byQuantity(movements, asOf) : byValue(indexer, rate, movements, rates, asOf);
    }

    // ---------------------------------------------------------------- por valor

    private static Valuation byValue(Indexer indexer, BigDecimal rate, List<InvestmentMovement> movements,
                                     IndexRates rates, LocalDate asOf) {
        double balance = 0;
        BigDecimal invested = BigDecimal.ZERO;
        BigDecimal income = BigDecimal.ZERO;
        LocalDate cursor = null;
        LocalDate lastUpdate = null;
        double r = rate == null ? 0 : rate.doubleValue();
        for (InvestmentMovement m : movements) {
            if (m.getDate().isAfter(asOf)) continue;
            if (cursor != null) balance *= factor(indexer, r, rates, cursor, m.getDate());
            cursor = m.getDate();
            switch (m.getKind()) {
                case APORTE -> {
                    balance += m.getAmount().doubleValue();
                    invested = invested.add(m.getAmount());
                }
                case RESGATE -> {
                    balance = Math.max(0, balance - m.getAmount().doubleValue());
                    invested = invested.subtract(m.getAmount());
                }
                case ATUALIZACAO -> {
                    balance = m.getAmount().doubleValue();
                    lastUpdate = m.getDate();
                }
                case PROVENTO -> income = income.add(m.getAmount());
                default -> throw new IllegalStateException("Movimento " + m.getKind() + " em investimento por valor");
            }
        }
        if (cursor != null) balance *= factor(indexer, r, rates, cursor, asOf);
        boolean automatic = indexer != null && indexer != Indexer.MANUAL;
        LocalDate until = automatic ? rates.lastBusinessDay() : null;
        return new Valuation(invested, money(balance), null, null, null, null, null, income, lastUpdate, until);
    }

    /** Fator de rendimento de {@code from} (inclusive) até {@code to} (exclusive). */
    static double factor(Indexer indexer, double rate, IndexRates rates, LocalDate from, LocalDate to) {
        if (indexer == null || !from.isBefore(to) || rates.cdi().isEmpty()) return 1;
        LocalDate lastDay = rates.lastBusinessDay();
        // Não rende além do último dia útil com taxa publicada.
        LocalDate end = to.isAfter(lastDay.plusDays(1)) ? lastDay.plusDays(1) : to;
        if (!from.isBefore(end)) return 1;
        double f = 1;
        switch (indexer) {
            case CDI -> {
                for (double d : rates.cdi().subMap(from, true, end, false).values()) f *= 1 + d / 100 * rate / 100;
            }
            case SELIC -> {
                for (double d : rates.selic().subMap(from, true, end, false).values()) f *= 1 + d / 100 * rate / 100;
            }
            case PRE -> f = Math.pow(1 + rate / 100, businessDays(rates, from, end) / 252.0);
            case IPCA -> {
                for (LocalDate d = from; d.isBefore(end); d = d.plusDays(1)) {
                    Double ipca = rates.ipcaOf(YearMonth.from(d));
                    if (ipca != null) f *= Math.pow(1 + ipca / 100, 1.0 / d.lengthOfMonth());
                }
                f *= Math.pow(1 + rate / 100, businessDays(rates, from, end) / 252.0);
            }
            case POUPANCA -> {
                for (LocalDate d = from; d.isBefore(end); d = d.plusDays(1)) {
                    Double selic = rates.selicOn(d);
                    if (selic == null) continue;
                    double annual = Math.pow(1 + selic / 100, 252) - 1;
                    double monthly = annual > 0.085 ? 0.005 : Math.pow(1 + 0.7 * annual, 1.0 / 12) - 1;
                    f *= Math.pow(1 + monthly, 1.0 / d.lengthOfMonth());
                }
            }
            case MANUAL -> f = 1;
        }
        return f;
    }

    private static int businessDays(IndexRates rates, LocalDate from, LocalDate to) {
        return rates.cdi().subMap(from, true, to, false).size();
    }

    // ---------------------------------------------------------------- por quantidade

    private static Valuation byQuantity(List<InvestmentMovement> movements, LocalDate asOf) {
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal lastPrice = null;
        LocalDate lastPriceDate = null;
        LocalDate lastUpdate = null;
        for (InvestmentMovement m : movements) {
            if (m.getDate().isAfter(asOf)) continue;
            switch (m.getKind()) {
                case COMPRA -> {
                    qty = qty.add(m.getQuantity());
                    cost = cost.add(m.getQuantity().multiply(m.getUnitPrice(), MC)).add(m.getFees());
                    lastPrice = m.getUnitPrice();
                    lastPriceDate = m.getDate();
                }
                case VENDA -> {
                    if (m.getQuantity().compareTo(qty) > 0) {
                        throw ApiException.badRequest("Venda de " + plain(m.getQuantity()) + " em " + br(m.getDate())
                                + " é maior que a quantidade em carteira (" + plain(qty) + ").");
                    }
                    BigDecimal avg = cost.divide(qty, MC);
                    BigDecimal costOut = avg.multiply(m.getQuantity(), MC);
                    realized = realized.add(m.getQuantity().multiply(m.getUnitPrice(), MC)).subtract(m.getFees())
                            .subtract(costOut);
                    qty = qty.subtract(m.getQuantity());
                    cost = qty.signum() == 0 ? BigDecimal.ZERO : cost.subtract(costOut);
                    lastPrice = m.getUnitPrice();
                    lastPriceDate = m.getDate();
                }
                case PROVENTO -> income = income.add(m.getAmount());
                case ATUALIZACAO -> {
                    if (m.getUnitPrice() == null) {
                        throw ApiException.badRequest("Em ações, FIIs e cripto a atualização é pela cotação (preço).");
                    }
                    lastPrice = m.getUnitPrice();
                    lastPriceDate = m.getDate();
                    lastUpdate = m.getDate();
                }
                default -> throw new IllegalStateException("Movimento " + m.getKind() + " em investimento por quantidade");
            }
        }
        BigDecimal value = lastPrice == null ? BigDecimal.ZERO : qty.multiply(lastPrice, MC);
        BigDecimal avg = qty.signum() > 0 ? cost.divide(qty, MC).setScale(8, RoundingMode.HALF_EVEN) : null;
        return new Valuation(cost.setScale(2, RoundingMode.HALF_EVEN), value.setScale(2, RoundingMode.HALF_EVEN),
                qty.stripTrailingZeros(), avg, lastPrice, lastPriceDate, realized.setScale(2, RoundingMode.HALF_EVEN),
                income, lastUpdate, null);
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_EVEN);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String br(LocalDate d) {
        return String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
    }
}
