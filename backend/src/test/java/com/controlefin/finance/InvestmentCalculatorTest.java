package com.controlefin.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.controlefin.common.ApiException;
import com.controlefin.finance.FinanceEnums.Indexer;
import com.controlefin.finance.FinanceEnums.InvestmentType;
import com.controlefin.finance.FinanceEnums.MovementKind;
import com.controlefin.finance.FinanceEnums.Origin;

/** Valor de investimentos: rendimento por CDI/pré/IPCA, aportes/resgates/atualização e preço médio de ações. */
class InvestmentCalculatorTest {

    private static final UUID ME = UUID.randomUUID();
    private static final UUID INV = UUID.randomUUID();
    private static final LocalDate D1 = LocalDate.of(2026, 11, 2);

    /** CDI de 0,05% ao dia em 20 dias úteis seguidos a partir de D1 (seg a sex). */
    private static IndexRates rates() {
        TreeMap<LocalDate, Double> cdi = new TreeMap<>();
        LocalDate d = D1;
        while (cdi.size() < 20) {
            if (d.getDayOfWeek().getValue() <= 5) cdi.put(d, 0.05);
            d = d.plusDays(1);
        }
        TreeMap<YearMonth, Double> ipca = new TreeMap<>();
        ipca.put(YearMonth.of(2026, 10), 0.30);
        return new IndexRates(cdi, new TreeMap<>(cdi), ipca);
    }

    private static InvestmentMovement mov(MovementKind kind, LocalDate date, String amount) {
        InvestmentMovement m = new InvestmentMovement(ME, INV, Origin.WEB);
        m.setKind(kind);
        m.setDate(date);
        m.setAmount(amount == null ? null : new BigDecimal(amount));
        return m;
    }

    private static InvestmentMovement trade(MovementKind kind, LocalDate date, String qty, String price, String fees) {
        InvestmentMovement m = mov(kind, date, null);
        m.setQuantity(new BigDecimal(qty));
        m.setUnitPrice(new BigDecimal(price));
        m.setFees(new BigDecimal(fees));
        BigDecimal gross = m.getQuantity().multiply(m.getUnitPrice());
        m.setAmount(kind == MovementKind.COMPRA ? gross.add(m.getFees()) : gross.subtract(m.getFees()));
        return m;
    }

    private static InvestmentCalculator.Valuation fixed(Indexer idx, String rate, List<InvestmentMovement> ms, LocalDate asOf) {
        return InvestmentCalculator.value(InvestmentType.CAIXINHA, idx, rate == null ? null : new BigDecimal(rate), ms,
                rates(), asOf);
    }

    @Test
    void caixinha100PorCentoDoCdiRendeNosDiasUteis() {
        // Aporte em D1 (segunda); até sexta (exclusive) = 4 dias úteis de 0,05%.
        var v = fixed(Indexer.CDI, "100", List.of(mov(MovementKind.APORTE, D1, "1000")), D1.plusDays(4));
        assertEquals(new BigDecimal("1002.00"), v.currentValue()); // 1000 × 1,0005^4 = 1002,0015
        assertEquals(new BigDecimal("1000"), v.invested());
        assertEquals(rates().lastBusinessDay(), v.ratesUntil());
    }

    @Test
    void cdbA110PorCentoRendeMais() {
        var v = fixed(Indexer.CDI, "110", List.of(mov(MovementKind.APORTE, D1, "1000")), D1.plusDays(4));
        assertEquals(new BigDecimal("1002.20"), v.currentValue()); // 1000 × 1,00055^4
    }

    @Test
    void naoRendeAlemDoUltimoDiaComCdiPublicado() {
        var semana = fixed(Indexer.CDI, "100", List.of(mov(MovementKind.APORTE, D1, "1000")), LocalDate.of(2027, 6, 1));
        double esperado = 1000 * Math.pow(1.0005, 20);
        assertEquals(BigDecimal.valueOf(esperado).setScale(2, java.math.RoundingMode.HALF_EVEN), semana.currentValue());
    }

    @Test
    void resgateTiraDoSaldoEAtualizacaoTrocaOValor() {
        List<InvestmentMovement> ms = new ArrayList<>();
        ms.add(mov(MovementKind.APORTE, D1, "1000"));
        ms.add(mov(MovementKind.RESGATE, D1, "400"));
        var v = fixed(Indexer.CDI, "100", ms, D1);
        assertEquals(new BigDecimal("600.00"), v.currentValue());
        assertEquals(new BigDecimal("600"), v.invested());

        // O app do banco mostra 650 em D1+1: a partir dali rende sobre 650.
        ms.add(mov(MovementKind.ATUALIZACAO, D1.plusDays(1), "650"));
        var w = fixed(Indexer.CDI, "100", ms, D1.plusDays(1));
        assertEquals(new BigDecimal("650.00"), w.currentValue());
        assertEquals(D1.plusDays(1), w.lastUpdate());
        assertEquals(new BigDecimal("50.00"), w.currentValue().subtract(w.invested()).setScale(2));
    }

    @Test
    void manualSoMudaComAtualizacao() {
        List<InvestmentMovement> ms = new ArrayList<>(List.of(mov(MovementKind.APORTE, D1, "5000")));
        assertEquals(new BigDecimal("5000.00"), fixed(Indexer.MANUAL, null, ms, D1.plusDays(10)).currentValue());
        ms.add(mov(MovementKind.ATUALIZACAO, D1.plusDays(5), "5120.40"));
        var v = fixed(Indexer.MANUAL, null, ms, D1.plusDays(10));
        assertEquals(new BigDecimal("5120.40"), v.currentValue());
        assertNull(v.ratesUntil());
    }

    @Test
    void prefixadoUsa252DiasUteis() {
        double f = InvestmentCalculator.factor(Indexer.PRE, 12, rates(), D1, D1.plusDays(30));
        assertEquals(Math.pow(1.12, 20 / 252.0), f, 1e-12);
    }

    @Test
    void ipcaMaisSpreadUsaOUltimoIpcaConhecido() {
        // Novembro sem IPCA publicado: usa o de outubro (0,30%) pró-rata por dia corrido.
        LocalDate to = D1.plusDays(10);
        double f = InvestmentCalculator.factor(Indexer.IPCA, 6, rates(), D1, to);
        double ipca = Math.pow(1.003, 10.0 / 30);
        double spread = Math.pow(1.06, 8 / 252.0); // 8 dias úteis em 10 dias corridos
        assertEquals(ipca * spread, f, 1e-12);
    }

    @Test
    void acoesPrecoMedioVendaEProventos() {
        List<InvestmentMovement> ms = List.of(
                trade(MovementKind.COMPRA, D1, "10", "10", "1"),          // custo 101
                trade(MovementKind.COMPRA, D1.plusDays(1), "10", "12", "0"), // custo 221, médio 11,05
                trade(MovementKind.VENDA, D1.plusDays(2), "5", "15", "0"),   // realiza 75 − 55,25 = 19,75
                mov(MovementKind.PROVENTO, D1.plusDays(3), "4.50"));
        InvestmentMovement cot = mov(MovementKind.ATUALIZACAO, D1.plusDays(4), null);
        cot.setUnitPrice(new BigDecimal("13"));
        List<InvestmentMovement> all = new ArrayList<>(ms);
        all.add(cot);
        var v = InvestmentCalculator.value(InvestmentType.FII, null, null, all, IndexRates.EMPTY, D1.plusDays(5));
        assertEquals(0, new BigDecimal("15").compareTo(v.quantity()));
        assertEquals(new BigDecimal("11.05000000"), v.averagePrice());
        assertEquals(new BigDecimal("165.75"), v.invested());
        assertEquals(new BigDecimal("195.00"), v.currentValue());
        assertEquals(new BigDecimal("19.75"), v.realizedGain());
        assertEquals(new BigDecimal("4.50"), v.income());
        assertEquals(new BigDecimal("13"), v.lastPrice());
    }

    @Test
    void vendaMaiorQueAPosicaoEhRecusada() {
        List<InvestmentMovement> ms = List.of(trade(MovementKind.COMPRA, D1, "3", "100", "0"),
                trade(MovementKind.VENDA, D1.plusDays(1), "4", "110", "0"));
        var e = assertThrows(ApiException.class,
                () -> InvestmentCalculator.value(InvestmentType.ACAO, null, null, ms, IndexRates.EMPTY, LocalDate.MAX));
        assertTrue(e.getMessage().contains("maior que a quantidade"));
    }

    @Test
    void criptoAceitaFracao() {
        var v = InvestmentCalculator.value(InvestmentType.CRIPTO, null, null,
                List.of(trade(MovementKind.COMPRA, D1, "0.00153", "350000", "0")), IndexRates.EMPTY, D1);
        assertEquals(new BigDecimal("535.50"), v.currentValue());
    }

    @Test
    void leASerieDoBancoCentral() {
        var list = IndexRateService.parse("[{\"data\":\"24/11/2026\",\"valor\":\"0.050788\"},"
                + "{\"data\":\"01/10/2026\",\"valor\":\"0.14\"}]");
        assertEquals(2, list.size());
        assertEquals(LocalDate.of(2026, 11, 24), list.get(0).date());
        assertEquals(0.050788, list.get(0).value(), 1e-12);
    }
}
