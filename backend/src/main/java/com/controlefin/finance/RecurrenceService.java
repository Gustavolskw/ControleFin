package com.controlefin.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.controlefin.common.ApiException;
import com.controlefin.common.Money;
import com.controlefin.finance.FinanceDtos.InvoiceSummary;
import com.controlefin.finance.FinanceDtos.OccurrenceResponse;
import com.controlefin.finance.FinanceDtos.RecurrenceLinkRequest;
import com.controlefin.finance.FinanceDtos.RecurrenceMonth;
import com.controlefin.finance.FinanceDtos.RecurrenceRequest;
import com.controlefin.finance.FinanceDtos.RecurrenceResponse;
import com.controlefin.finance.FinanceDtos.TransactionResponse;
import com.controlefin.finance.FinanceEnums.CategoryKind;
import com.controlefin.finance.FinanceEnums.Frequency;
import com.controlefin.finance.FinanceEnums.OccurrenceStatus;
import com.controlefin.finance.FinanceEnums.TransactionKind;

/**
 * Recorrências: previsto × realizado.
 *
 * <p>As ocorrências não são gravadas: saem da regra (frequência, dia, início e fim). Cada ocorrência fica REALIZADA
 * quando um lançamento é ligado a ela (recurrence_id + recurrence_date, único no banco), PULADA quando o usuário pula
 * o mês, ATRASADA se a data passou e PREVISTA se ainda vai acontecer.
 *
 * <p>Duplicidade: antes de lançar, procure lançamentos parecidos ainda sem vínculo ({@link #candidates}) e vincule
 * em vez de lançar de novo.
 */
@Service
public class RecurrenceService {

    /** Janela (dias) para procurar lançamentos parecidos com uma ocorrência. */
    static final int MATCH_DAYS = 7;
    /** Diferença de valor aceita (moeda estrangeira e contas de consumo variam). */
    static final BigDecimal MATCH_TOLERANCE = new BigDecimal("0.25");

    private final RecurrenceRepository recurrences;
    private final TransactionRepository transactions;
    private final WalletRepository wallets;
    private final AccountRepository accounts;
    private final CardRepository cards;
    private final CategoryRepository categories;
    private final TransactionService transactionService;

    public RecurrenceService(RecurrenceRepository recurrences, TransactionRepository transactions,
                             WalletRepository wallets, AccountRepository accounts, CardRepository cards,
                             CategoryRepository categories, TransactionService transactionService) {
        this.recurrences = recurrences;
        this.transactions = transactions;
        this.wallets = wallets;
        this.accounts = accounts;
        this.cards = cards;
        this.categories = categories;
        this.transactionService = transactionService;
    }

    // ---------------------------------------------------------------- calendário (regras puras)

    /** Datas das ocorrências entre from e to (inclusive), respeitando início e fim da recorrência. */
    static List<LocalDate> occurrences(Recurrence r, LocalDate from, LocalDate to) {
        LocalDate start = r.getStartDate();
        LocalDate end = r.getEndDate() == null || r.getEndDate().isAfter(to) ? to : r.getEndDate();
        LocalDate lower = from.isAfter(start) ? from : start;
        List<LocalDate> out = new ArrayList<>();
        if (end.isBefore(lower)) return out;
        int interval = Math.max(1, r.getIntervalCount());
        if (r.getFrequency() == Frequency.SEMANAL) {
            long step = 7L * interval;
            long k = Math.max(0, ChronoUnit.DAYS.between(start, lower) / step);
            for (LocalDate d = start.plusDays(k * step); !d.isAfter(end); d = d.plusDays(step)) {
                if (!d.isBefore(lower)) out.add(d);
            }
            return out;
        }
        int months = r.getFrequency() == Frequency.ANUAL ? 12 * interval : interval;
        int day = r.getDayOfMonth() == null ? start.getDayOfMonth() : r.getDayOfMonth();
        YearMonth first = YearMonth.from(start);
        long k = Math.max(0, ChronoUnit.MONTHS.between(first, YearMonth.from(lower)) / months - 1);
        for (YearMonth ym = first.plusMonths(k * months); !ym.atDay(1).isAfter(end); ym = ym.plusMonths(months)) {
            LocalDate d = InvoiceService.dayIn(ym, day);
            if (!d.isBefore(lower) && !d.isAfter(end)) out.add(d);
        }
        return out;
    }

    static boolean occursOn(Recurrence r, LocalDate date) {
        return occurrences(r, date, date).contains(date);
    }

    // ---------------------------------------------------------------- cadastro

    @Transactional(readOnly = true)
    public List<RecurrenceResponse> list(UUID userId) {
        Ctx c = ctx(userId);
        return recurrences.findByUserIdOrderByDescriptionAsc(userId).stream().map(r -> toResponse(r, c))
                .sorted(Comparator.comparing(RecurrenceResponse::active).reversed()).toList();
    }

    @Transactional
    public RecurrenceResponse create(UUID userId, RecurrenceRequest req) {
        Recurrence r = new Recurrence(userId);
        apply(userId, r, req, true);
        recurrences.saveAndFlush(r);
        return toResponse(r, ctx(userId));
    }

    @Transactional
    public RecurrenceResponse update(UUID userId, UUID id, RecurrenceRequest req) {
        Recurrence r = recurrence(userId, id);
        apply(userId, r, req, false);
        r.setActive(req.active() == null || req.active());
        recurrences.flush();
        return toResponse(r, ctx(userId));
    }

    /** Só exclui recorrência sem lançamentos; com histórico, desative ou informe a data de fim. */
    @Transactional
    public void delete(UUID userId, UUID id) {
        Recurrence r = recurrence(userId, id);
        if (transactions.existsByUserIdAndRecurrenceId(userId, id)) {
            throw ApiException.badRequest("Esta recorrência já tem lançamentos. Desative-a ou informe a data de fim.");
        }
        recurrences.delete(r);
    }

    Recurrence recurrence(UUID userId, UUID id) {
        return recurrences.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Recorrência não encontrada."));
    }

    private void apply(UUID userId, Recurrence r, RecurrenceRequest req, boolean isNew) {
        TransactionKind kind = req.kind();
        if (kind != TransactionKind.RECEITA && kind != TransactionKind.DESPESA) {
            throw ApiException.badRequest("Recorrência é uma receita ou uma despesa.");
        }
        if (req.endDate() != null && req.endDate().isBefore(req.startDate())) {
            throw ApiException.badRequest("A data de fim é anterior à de início.");
        }
        if ((req.walletId() == null) == (req.cardId() == null)) {
            throw ApiException.badRequest("Escolha a conta ou o cartão.");
        }
        boolean credit = false;
        UUID walletId = null;
        UUID cardId = null;
        if (req.cardId() != null) {
            if (kind != TransactionKind.DESPESA) {
                throw ApiException.badRequest("Receita recorrente entra numa conta, não num cartão.");
            }
            Card card = cards.findByIdAndUserId(req.cardId(), userId)
                    .orElseThrow(() -> ApiException.notFound("Cartão não encontrado."));
            if (card.isArchived() && (isNew || !card.getId().equals(r.getCardId()))) {
                throw ApiException.badRequest("O cartão \"" + card.getName() + "\" está arquivado.");
            }
            credit = req.credit() != null ? req.credit() : card.hasCredit();
            if (credit && !card.hasCredit()) {
                throw ApiException.badRequest("O cartão \"" + card.getName() + "\" não tem função crédito.");
            }
            if (!credit && !card.hasDebit()) {
                throw ApiException.badRequest("O cartão \"" + card.getName() + "\" não tem carteira para débito.");
            }
            cardId = card.getId();
        } else {
            Wallet w = wallets.findByIdAndUserId(req.walletId(), userId)
                    .orElseThrow(() -> ApiException.notFound("Carteira não encontrada."));
            if (w.isArchived() && (isNew || !w.getId().equals(r.getWalletId()))) {
                throw ApiException.badRequest("A carteira \"" + w.getName() + "\" está arquivada.");
            }
            walletId = w.getId();
        }

        UUID categoryId = null;
        if (req.categoryId() != null) {
            Category c = categories.findByIdAndUserId(req.categoryId(), userId)
                    .orElseThrow(() -> ApiException.notFound("Categoria não encontrada."));
            CategoryKind expected = kind == TransactionKind.RECEITA ? CategoryKind.RECEITA : CategoryKind.DESPESA;
            if (c.getKind() != expected) {
                throw ApiException.badRequest("A categoria \"" + c.getName() + "\" não é de "
                        + (expected == CategoryKind.RECEITA ? "receita." : "despesa."));
            }
            categoryId = c.getId();
        }

        String currency = Money.trimToNull(req.originalCurrency());
        if (currency != null) {
            currency = currency.toUpperCase();
            if (kind != TransactionKind.DESPESA) throw ApiException.badRequest("Moeda estrangeira só em despesas.");
            if ("BRL".equals(currency)) throw ApiException.badRequest("Para valores em reais, não informe moeda.");
            if (req.originalAmount() == null) throw ApiException.badRequest("Informe o valor na moeda original.");
        } else if (req.originalAmount() != null) {
            throw ApiException.badRequest("Informe a moeda do valor original.");
        }

        r.setKind(kind);
        r.setDescription(req.description().trim());
        r.setAmount(Money.of(req.amount()));
        r.setFrequency(req.frequency());
        r.setIntervalCount(req.intervalCount() == null ? 1 : req.intervalCount());
        r.setDayOfMonth(req.frequency() == Frequency.SEMANAL ? null
                : req.dayOfMonth() != null ? req.dayOfMonth() : req.startDate().getDayOfMonth());
        r.setStartDate(req.startDate());
        r.setEndDate(req.endDate());
        r.setWalletId(walletId);
        r.setCardId(cardId);
        r.setCredit(credit);
        r.setConsumesLimit(!credit || req.consumesLimit() == null || req.consumesLimit());
        r.setCategoryId(categoryId);
        r.setForeign(currency, currency == null ? null : Money.of(req.originalAmount()));
        r.setNotes(Money.trimToNull(req.notes()));
    }

    // ---------------------------------------------------------------- previsto × realizado

    /** Ocorrências do mês com situação, lançamento ligado e candidatos a duplicado. */
    @Transactional(readOnly = true)
    public RecurrenceMonth month(UUID userId, YearMonth ym) {
        Ctx c = ctx(userId);
        List<OccurrenceResponse> occ = occurrences(userId, c, ym.atDay(1), ym.atEndOfMonth(), true);
        BigDecimal eExp = Money.nz(null), rExp = Money.nz(null), pExp = Money.nz(null);
        BigDecimal eInc = Money.nz(null), rInc = Money.nz(null), pInc = Money.nz(null);
        for (OccurrenceResponse o : occ) {
            if (o.status() == OccurrenceStatus.PULADA) continue;
            boolean income = o.kind() == TransactionKind.RECEITA;
            BigDecimal real = o.status() == OccurrenceStatus.REALIZADA ? o.actualAmount() : null;
            if (income) {
                eInc = eInc.add(o.expectedAmount());
                if (real != null) rInc = rInc.add(real); else pInc = pInc.add(o.expectedAmount());
            } else {
                eExp = eExp.add(o.expectedAmount());
                if (real != null) rExp = rExp.add(real); else pExp = pExp.add(o.expectedAmount());
            }
        }
        List<RecurrenceResponse> list = c.recurrences().stream().map(r -> toResponse(r, c))
                .sorted(Comparator.comparing(RecurrenceResponse::active).reversed()).toList();
        return new RecurrenceMonth(ym.toString(), eExp, rExp, pExp, eInc, rInc, pInc, occ, list);
    }

    /** Próximas ocorrências (e atrasadas dos últimos 60 dias) ainda não lançadas, para o painel. */
    @Transactional(readOnly = true)
    public List<OccurrenceResponse> upcoming(UUID userId, int days, int limit) {
        LocalDate today = Money.today();
        Ctx c = ctx(userId);
        return occurrences(userId, c, today.minusDays(60), today.plusDays(days), false).stream()
                .filter(o -> o.status() == OccurrenceStatus.ATRASADA || o.status() == OccurrenceStatus.PREVISTA)
                .limit(limit).toList();
    }

    /**
     * Ajuste da previsão de saldo no fim do mês: recorrências em conta (ou débito) do mês ainda não lançadas
     * (receitas somam, despesas subtraem). Crédito não entra: cai na fatura.
     */
    @Transactional(readOnly = true)
    public BigDecimal pendingWalletImpact(UUID userId, YearMonth ym) {
        Ctx c = ctx(userId);
        BigDecimal sum = Money.nz(null);
        for (OccurrenceResponse o : occurrences(userId, c, ym.atDay(1), ym.atEndOfMonth(), false)) {
            if (o.credit() || (o.status() != OccurrenceStatus.ATRASADA && o.status() != OccurrenceStatus.PREVISTA)) continue;
            sum = o.kind() == TransactionKind.RECEITA ? sum.add(o.expectedAmount()) : sum.subtract(o.expectedAmount());
        }
        return sum;
    }

    /**
     * Limite reservado por recorrências no crédito que reservam limite: ocorrências ainda não lançadas que caem na
     * fatura aberta (entre o fechamento anterior e o atual).
     */
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> reservedByCreditLine(UUID userId, Map<UUID, InvoiceSummary> openByLine,
                                                      Map<UUID, CreditLine> lines) {
        Map<UUID, BigDecimal> out = new HashMap<>();
        Ctx c = ctx(userId);
        for (Recurrence r : c.recurrences()) {
            if (!r.isActive() || !r.isCredit() || !r.isConsumesLimit()) continue;
            Card card = c.cards().get(r.getCardId());
            if (card == null || card.getCreditLineId() == null) continue;
            InvoiceSummary open = openByLine.get(card.getCreditLineId());
            CreditLine line = lines.get(card.getCreditLineId());
            if (open == null || line == null) continue;
            YearMonth ref = YearMonth.parse(open.reference());
            LocalDate from = InvoiceService.closingDate(ref.minusMonths(1), line.getClosingDay(), line.getDueDay());
            for (LocalDate d : occurrences(r, from, open.closingDate().minusDays(1))) {
                if (c.realized().containsKey(key(r.getId(), d)) || c.skips().contains(key(r.getId(), d))) continue;
                out.merge(card.getCreditLineId(), r.getAmount(), BigDecimal::add);
            }
        }
        return out;
    }

    private List<OccurrenceResponse> occurrences(UUID userId, Ctx c, LocalDate from, LocalDate to,
                                                 boolean withCandidates) {
        LocalDate today = Money.today();
        List<OccurrenceResponse> out = new ArrayList<>();
        for (Recurrence r : c.recurrences()) {
            for (LocalDate d : occurrences(r, from, to)) {
                Transaction t = c.realized().get(key(r.getId(), d));
                if (!r.isActive() && t == null) continue;
                OccurrenceStatus st = t != null ? OccurrenceStatus.REALIZADA
                        : c.skips().contains(key(r.getId(), d)) ? OccurrenceStatus.PULADA
                        : d.isBefore(today) ? OccurrenceStatus.ATRASADA : OccurrenceStatus.PREVISTA;
                int cand = withCandidates && (st == OccurrenceStatus.ATRASADA || st == OccurrenceStatus.PREVISTA)
                        ? candidateRows(userId, r, d).size() : 0;
                out.add(new OccurrenceResponse(r.getId(), r.getDescription(), r.getKind(), d, r.getAmount(), st,
                        t == null ? null : t.getId(), t == null ? null : t.getAmount(), paidWith(r, c), r.isCredit(),
                        cand, r.getOriginalCurrency(), r.getOriginalAmount()));
            }
        }
        out.sort(Comparator.comparing(OccurrenceResponse::date).thenComparing(OccurrenceResponse::description));
        return out;
    }

    // ---------------------------------------------------------------- pular, vincular, candidatos

    @Transactional
    public void skip(UUID userId, UUID id, LocalDate date) {
        Recurrence r = recurrence(userId, id);
        requireOccurrence(r, date);
        if (transactions.occurrenceTaken(userId, id, date, UUID.randomUUID())) {
            throw ApiException.badRequest("Esta ocorrência já foi lançada; cancele o lançamento antes de pular.");
        }
        recurrences.skip(id, userId, date);
    }

    @Transactional
    public void unskip(UUID userId, UUID id, LocalDate date) {
        recurrence(userId, id);
        recurrences.unskip(id, date);
    }

    /** Lançamentos parecidos ainda sem vínculo (mesmo tipo, data próxima, valor próximo, mesma conta/cartão). */
    @Transactional(readOnly = true)
    public List<TransactionResponse> candidates(UUID userId, UUID id, LocalDate date) {
        Recurrence r = recurrence(userId, id);
        requireOccurrence(r, date);
        var names = transactionService.names(userId);
        return candidateRows(userId, r, date).stream().map(t -> TransactionService.toResponse(t, names)).toList();
    }

    List<Transaction> candidateRows(UUID userId, Recurrence r, LocalDate date) {
        BigDecimal tolerance = r.getAmount().multiply(MATCH_TOLERANCE).setScale(2, RoundingMode.HALF_EVEN);
        return transactions.sameKindBetween(userId, r.getKind(), date.minusDays(MATCH_DAYS), date.plusDays(MATCH_DAYS))
                .stream()
                .filter(t -> t.getRecurrenceId() == null && t.getInstallmentGroup() == null)
                .filter(t -> t.getAmount().subtract(r.getAmount()).abs().compareTo(tolerance) <= 0)
                .filter(t -> sameSource(r, t) || similar(r.getDescription(), t.getDescription()))
                .sorted(Comparator.comparing((Transaction t) -> t.getAmount().subtract(r.getAmount()).abs())
                        .thenComparing(t -> Math.abs(ChronoUnit.DAYS.between(date, t.getDate()))))
                .limit(5).toList();
    }

    private static boolean sameSource(Recurrence r, Transaction t) {
        return r.getCardId() != null ? r.getCardId().equals(t.getCardId())
                : r.getWalletId().equals(t.getWalletId()) && t.getCardId() == null;
    }

    /** Descrições parecidas: uma contém a primeira palavra (com 3+ letras) da outra, sem diferenciar maiúsculas. */
    static boolean similar(String a, String b) {
        String x = a.toLowerCase().trim(), y = b.toLowerCase().trim();
        String wx = x.split("\\s+")[0], wy = y.split("\\s+")[0];
        return (wx.length() >= 3 && y.contains(wx)) || (wy.length() >= 3 && x.contains(wy));
    }

    /** Liga (ou desliga, com campos nulos) um lançamento existente a uma ocorrência. */
    @Transactional
    public TransactionResponse link(UUID userId, UUID transactionId, RecurrenceLinkRequest req) {
        Transaction t = transactions.findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> ApiException.notFound("Lançamento não encontrado."));
        if (req == null || req.recurrenceId() == null) {
            t.setRecurrence(null, null);
        } else {
            Recurrence r = recurrence(userId, req.recurrenceId());
            validateOccurrence(userId, r, req.recurrenceDate(), t);
            t.setRecurrence(r.getId(), req.recurrenceDate());
            recurrences.unskip(r.getId(), req.recurrenceDate());
        }
        t.touch();
        transactions.flush();
        return TransactionService.toResponse(t, transactionService.names(userId));
    }

    private void validateOccurrence(UUID userId, Recurrence r, LocalDate date, Transaction t) {
        checkOccurrence(transactions, userId, r, date, t.getKind(), t.getInstallmentGroup() != null, t.getId());
    }

    /** Regras para ligar um lançamento a uma ocorrência (ao criar o lançamento ou ao vincular depois). */
    static void checkOccurrence(TransactionRepository transactions, UUID userId, Recurrence r, LocalDate date,
                                TransactionKind kind, boolean installments, UUID exceptId) {
        if (date == null) throw ApiException.badRequest("Informe a data da ocorrência.");
        requireOccurrence(r, date);
        if (kind != r.getKind()) {
            throw ApiException.badRequest("O lançamento e a recorrência precisam ser do mesmo tipo.");
        }
        if (installments) {
            throw ApiException.badRequest("Compra parcelada não pode realizar uma recorrência.");
        }
        if (transactions.occurrenceTaken(userId, r.getId(), date, exceptId == null ? UUID.randomUUID() : exceptId)) {
            throw ApiException.conflict("A ocorrência de " + date + " de \"" + r.getDescription() + "\" já foi lançada.");
        }
    }

    private static void requireOccurrence(Recurrence r, LocalDate date) {
        if (date == null || !occursOn(r, date)) {
            throw ApiException.badRequest("\"" + r.getDescription() + "\" não tem ocorrência em " + date + ".");
        }
    }

    // ---------------------------------------------------------------- contexto e respostas

    record Ctx(List<Recurrence> recurrences, Map<String, Transaction> realized, Set<String> skips,
               Map<UUID, Wallet> wallets, Map<UUID, Account> accounts, Map<UUID, Card> cards,
               Map<UUID, Category> categories) {
    }

    static String key(UUID recurrenceId, LocalDate date) {
        return recurrenceId + "|" + date;
    }

    private Ctx ctx(UUID userId) {
        Map<String, Transaction> realized = new HashMap<>();
        for (Transaction t : transactions.realized(userId, LocalDate.of(1900, 1, 1), LocalDate.of(9999, 12, 31))) {
            realized.put(key(t.getRecurrenceId(), t.getRecurrenceDate()), t);
        }
        Set<String> skips = new HashSet<>();
        for (var s : recurrences.skips(userId)) skips.add(key(s.getRecurrenceId(), s.getDate()));
        return new Ctx(recurrences.findByUserIdOrderByDescriptionAsc(userId), realized, skips,
                wallets.findByUserIdOrderByPositionAscNameAsc(userId).stream()
                        .collect(Collectors.toMap(Wallet::getId, Function.identity())),
                accounts.findByUserIdOrderByNameAsc(userId).stream()
                        .collect(Collectors.toMap(Account::getId, Function.identity())),
                cards.findByUserIdOrderByNameAsc(userId).stream()
                        .collect(Collectors.toMap(Card::getId, Function.identity())),
                categories.findByUserIdOrderByNameAsc(userId).stream()
                        .collect(Collectors.toMap(Category::getId, Function.identity())));
    }

    private static String paidWith(Recurrence r, Ctx c) {
        if (r.getCardId() != null) {
            Card card = c.cards().get(r.getCardId());
            String name = card == null ? "Cartão" : card.getName();
            return name + (r.isCredit() ? " — crédito" : " — débito");
        }
        Wallet w = c.wallets().get(r.getWalletId());
        if (w == null) return "";
        Account a = c.accounts().get(w.getAccountId());
        return a == null ? w.getName() : a.getName() + " · " + w.getName();
    }

    private static RecurrenceResponse toResponse(Recurrence r, Ctx c) {
        Category cat = r.getCategoryId() == null ? null : c.categories().get(r.getCategoryId());
        String catName = null;
        if (cat != null) {
            Category parent = cat.getParentId() == null ? null : c.categories().get(cat.getParentId());
            catName = parent == null ? cat.getName() : parent.getName() + " › " + cat.getName();
        }
        LocalDate today = Money.today();
        LocalDate next = null;
        if (r.isActive()) {
            for (LocalDate d : occurrences(r, today.minusDays(60), today.plusYears(2))) {
                String k = key(r.getId(), d);
                if (!c.realized().containsKey(k) && !c.skips().contains(k)) {
                    next = d;
                    break;
                }
            }
        }
        return new RecurrenceResponse(r.getId(), r.getKind(), r.getDescription(), r.getAmount(), r.getFrequency(),
                r.getIntervalCount(), r.getDayOfMonth(), r.getStartDate(), r.getEndDate(), r.getWalletId(),
                r.getCardId(), r.isCredit(), r.isConsumesLimit(), paidWith(r, c), r.getCategoryId(), catName,
                r.getOriginalCurrency(), r.getOriginalAmount(), r.getNotes(), r.isActive(), next);
    }
}
