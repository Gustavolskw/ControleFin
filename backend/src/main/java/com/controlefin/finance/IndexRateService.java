package com.controlefin.finance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.controlefin.common.Money;

/**
 * CDI, Selic e IPCA do Banco Central (API pública SGS, sem cadastro; nada do usuário é enviado). Sincroniza ao subir
 * e três vezes por dia; guarda em index_rates e mantém uma cópia em memória para os cálculos.
 */
@Service
public class IndexRateService {

    private static final Logger log = LoggerFactory.getLogger(IndexRateService.class);
    /** Série SGS de cada índice. */
    private static final Map<String, Integer> SERIES = Map.of("CDI", 12, "SELIC", 11, "IPCA", 433);
    /** Desde quando buscar na primeira vez. */
    static final LocalDate FIRST_DATE = LocalDate.of(2015, 1, 1);
    private static final DateTimeFormatter BR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Pattern ITEM = Pattern.compile(
            "\\{\\s*\"data\"\\s*:\\s*\"(\\d{2})/(\\d{2})/(\\d{4})\"\\s*,\\s*\"valor\"\\s*:\\s*\"(-?[0-9.]+)\"\\s*}");

    private final JdbcClient jdbc;
    private final boolean syncEnabled;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final Object syncLock = new Object();
    private volatile IndexRates cache;

    public IndexRateService(JdbcClient jdbc,
                            @Value("${app.rates.sync-enabled:true}") boolean syncEnabled,
                            @Value("${app.rates.url:https://api.bcb.gov.br/dados/serie/bcdata.sgs.%d/dados}") String baseUrl) {
        this.jdbc = jdbc;
        this.syncEnabled = syncEnabled;
        this.baseUrl = baseUrl;
    }

    /** Taxas em memória (carrega do banco na primeira vez). */
    public IndexRates rates() {
        IndexRates r = cache;
        if (r == null) {
            synchronized (this) {
                if (cache == null) cache = load();
                r = cache;
            }
        }
        return r;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        if (syncEnabled) Thread.ofVirtual().name("index-rates").start(this::sync);
    }

    @Scheduled(cron = "0 20 9,13,19 * * *", zone = "America/Sao_Paulo")
    public void scheduled() {
        if (syncEnabled) sync();
    }

    /** Busca o que falta de cada série (desde a última data guardada) e recarrega a memória. */
    public void sync() {
        synchronized (syncLock) {
            syncAll();
        }
    }

    private void syncAll() {
        int added = 0;
        for (var e : SERIES.entrySet()) {
            try {
                added += syncSeries(e.getKey(), e.getValue());
            } catch (Exception ex) {
                log.warn("Taxas do Banco Central ({}): {}", e.getKey(), ex.getMessage());
            }
        }
        cache = load();
        if (added > 0) log.info("Taxas do Banco Central: {} novas (CDI até {})", added, cache.lastBusinessDay());
    }

    private int syncSeries(String index, int series) throws Exception {
        List<LocalDate> max = jdbc.sql("select max(date) from index_rates where index = :i").param("i", index)
                .query((rs, n) -> rs.getObject(1, LocalDate.class)).list();
        LocalDate last = max.isEmpty() ? null : max.get(0);
        LocalDate today = Money.today();
        LocalDate start = last == null ? FIRST_DATE : last.plusDays(1);
        int added = 0;
        while (!start.isAfter(today)) {
            // Séries diárias: janela de no máximo 10 anos por consulta.
            LocalDate end = start.plusYears(9).isAfter(today) ? today : start.plusYears(9);
            for (Rate r : fetch(series, start, end)) {
                added += jdbc.sql("insert into index_rates (index, date, value) values (:i, :d, :v) on conflict do nothing")
                        .param("i", index).param("d", r.date()).param("v", r.value()).update();
            }
            start = end.plusDays(1);
        }
        return added;
    }

    record Rate(LocalDate date, double value) {
    }

    private List<Rate> fetch(int series, LocalDate from, LocalDate to) throws Exception {
        String url = String.format(baseUrl, series) + "?formato=json&dataInicial=" + from.format(BR)
                + "&dataFinal=" + to.format(BR);
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30)).header("Accept", "application/json").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 404) return List.of(); // período sem dados (ex.: fim de semana)
                if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode());
                String body = res.body() == null ? "" : res.body().trim();
                // A API às vezes responde vazio: tenta de novo.
                if (!body.startsWith("[")) throw new IllegalStateException("resposta inválida");
                return parse(body);
            } catch (Exception e) {
                last = e;
                Thread.sleep(2000L * attempt);
            }
        }
        throw last;
    }

    static List<Rate> parse(String json) {
        List<Rate> out = new ArrayList<>();
        Matcher m = ITEM.matcher(json);
        while (m.find()) {
            LocalDate d = LocalDate.of(Integer.parseInt(m.group(3)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(1)));
            out.add(new Rate(d, Double.parseDouble(m.group(4))));
        }
        return out;
    }

    private IndexRates load() {
        NavigableMap<LocalDate, Double> cdi = new TreeMap<>();
        NavigableMap<LocalDate, Double> selic = new TreeMap<>();
        NavigableMap<YearMonth, Double> ipca = new TreeMap<>();
        try {
            jdbc.sql("select index, date, value from index_rates").query((rs, n) -> {
                String i = rs.getString(1);
                LocalDate d = rs.getObject(2, LocalDate.class);
                double v = rs.getDouble(3);
                switch (i) {
                    case "CDI" -> cdi.put(d, v);
                    case "SELIC" -> selic.put(d, v);
                    default -> ipca.put(YearMonth.from(d), v);
                }
                return null;
            }).list();
        } catch (Exception e) {
            log.warn("Não foi possível carregar as taxas: {}", e.getMessage());
            return IndexRates.EMPTY;
        }
        return new IndexRates(cdi, selic, ipca);
    }
}
