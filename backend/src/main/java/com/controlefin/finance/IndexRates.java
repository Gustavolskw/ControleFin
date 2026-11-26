package com.controlefin.finance;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Taxas do Banco Central em memória: CDI e Selic em % ao dia útil (chave = dia útil), IPCA em % ao mês.
 * As datas do CDI também são o calendário de dias úteis.
 */
public record IndexRates(NavigableMap<LocalDate, Double> cdi, NavigableMap<LocalDate, Double> selic,
                         NavigableMap<YearMonth, Double> ipca) {

    public static final IndexRates EMPTY = new IndexRates(new TreeMap<>(), new TreeMap<>(), new TreeMap<>());

    public IndexRates {
        cdi = Collections.unmodifiableNavigableMap(cdi);
        selic = Collections.unmodifiableNavigableMap(selic);
        ipca = Collections.unmodifiableNavigableMap(ipca);
    }

    /** Último dia útil com CDI publicado (o rendimento estimado vai até ele). */
    public LocalDate lastBusinessDay() {
        return cdi.isEmpty() ? null : cdi.lastKey();
    }

    /** IPCA do mês; sem o mês publicado ainda, o último conhecido (estimativa). */
    Double ipcaOf(YearMonth month) {
        var e = ipca.floorEntry(month);
        if (e != null) return e.getValue();
        return ipca.isEmpty() ? null : ipca.firstEntry().getValue();
    }

    /** Selic do dia útil mais recente até a data. */
    Double selicOn(LocalDate date) {
        var e = selic.floorEntry(date);
        return e == null ? null : e.getValue();
    }
}
