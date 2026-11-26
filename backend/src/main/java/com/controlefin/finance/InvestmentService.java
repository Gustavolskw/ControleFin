package com.controlefin.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.controlefin.common.ApiException;
import com.controlefin.common.Money;
import com.controlefin.finance.FinanceEnums.Indexer;
import com.controlefin.finance.FinanceEnums.InvestmentType;
import com.controlefin.finance.FinanceEnums.MovementKind;
import com.controlefin.finance.FinanceEnums.Origin;
import com.controlefin.finance.FinanceEnums.TransactionKind;
import com.controlefin.finance.InvestmentCalculator.Valuation;
import com.controlefin.finance.InvestmentDtos.InvestmentDetail;
import com.controlefin.finance.InvestmentDtos.InvestmentRequest;
import com.controlefin.finance.InvestmentDtos.InvestmentResponse;
import com.controlefin.finance.InvestmentDtos.MovementRequest;
import com.controlefin.finance.InvestmentDtos.MovementResponse;
import com.controlefin.finance.InvestmentDtos.Portfolio;
import com.controlefin.finance.InvestmentDtos.TypeTotal;

/**
 * Investimentos de um usuário: cadastro, movimentos e valor atual (ver {@link InvestmentCalculator}).
 *
 * <p>Movimento com carteira gera um lançamento ligado: aporte/compra = APLICACAO (sai da conta, não é despesa);
 * resgate/venda = RESGATE (volta, não é receita); proventos = RECEITA. Esse lançamento só muda pelo movimento.
 */
@Service
public class InvestmentService {

    static final int DUPLICATE_DAYS = 3;

    private final InvestmentRepository investments;
    private final InvestmentMovementRepository movements;
    private final AccountRepository accounts;
    private final WalletRepository wallets;
    private final TransactionService transactions;
    private final IndexRateService rates;

    public InvestmentService(InvestmentRepository investments, InvestmentMovementRepository movements,
                             AccountRepository accounts, WalletRepository wallets, TransactionService transactions,
                             IndexRateService rates) {
        this.investments = investments;
        this.movements = movements;
        this.accounts = accounts;
        this.wallets = wallets;
        this.transactions = transactions;
        this.rates = rates;
    }

    // ---------------------------------------------------------------- consulta

    @Transactional(readOnly = true)
    public Portfolio portfolio(UUID userId) {
        Names n = names(userId);
        Map<UUID, List<InvestmentMovement>> byInvestment = movements.findByUserIdOrderByDateAscCreatedAtAsc(userId)
                .stream().collect(Collectors.groupingBy(InvestmentMovement::getInvestmentId));
        IndexRates r = rates.rates();
        LocalDate today = Money.today();
        List<InvestmentResponse> list = investments.findByUserIdOrderByNameAsc(userId).stream()
                .map(i -> toResponse(i, byInvestment.getOrDefault(i.getId(), List.of()), r, today, n))
                .sorted(Comparator.comparing(InvestmentResponse::archived)
                        .thenComparing(InvestmentResponse::currentValue, Comparator.reverseOrder()))
                .toList();

        BigDecimal invested = BigDecimal.ZERO, value = BigDecimal.ZERO, income = BigDecimal.ZERO;
        BigDecimal realized = BigDecimal.ZERO, fixed = BigDecimal.ZERO, variable = BigDecimal.ZERO;
        Map<InvestmentType, BigDecimal> byType = new EnumMap<>(InvestmentType.class);
        for (InvestmentResponse i : list) {
            if (i.archived()) continue;
            invested = invested.add(i.invested());
            value = value.add(i.currentValue());
            income = income.add(i.income());
            if (i.realizedGain() != null) realized = realized.add(i.realizedGain());
            if (i.byQuantity()) variable = variable.add(i.currentValue());
            else fixed = fixed.add(i.currentValue());
            byType.merge(i.type(), i.currentValue(), BigDecimal::add);
        }
        List<TypeTotal> types = byType.entrySet().stream().filter(e -> e.getValue().signum() != 0)
                .sorted(Map.Entry.<InvestmentType, BigDecimal>comparingByValue().reversed())
                .map(e -> new TypeTotal(e.getKey(), e.getValue())).toList();
        return new Portfolio(invested, value, value.subtract(invested), income, realized, fixed, variable, types,
                r.lastBusinessDay(), list);
    }

    /** Valor atual de tudo o que está investido (não arquivado), para o painel. */
    @Transactional(readOnly = true)
    public BigDecimal totalValue(UUID userId) {
        return portfolio(userId).currentValue();
    }

    @Transactional(readOnly = true)
    public InvestmentDetail detail(UUID userId, UUID id) {
        Investment inv = investment(userId, id);
        Names n = names(userId);
        List<InvestmentMovement> ms = movements.findByUserIdAndInvestmentIdOrderByDateAscCreatedAtAsc(userId, id);
        InvestmentResponse resp = toResponse(inv, ms, rates.rates(), Money.today(), n);
        List<MovementResponse> list = new ArrayList<>(ms.stream().map(m -> toResponse(m, n)).toList());
        java.util.Collections.reverse(list); // mais recentes primeiro
        return new InvestmentDetail(resp, list);
    }

    // ---------------------------------------------------------------- cadastro

    @Transactional
    public InvestmentResponse create(UUID userId, InvestmentRequest req) {
        String name = req.name().trim();
        if (investments.existsByUserIdAndNameIgnoreCase(userId, name)) {
            throw ApiException.conflict("Já existe um investimento chamado \"" + name + "\".");
        }
        Investment inv = new Investment(userId);
        apply(userId, inv, req, true);
        investments.saveAndFlush(inv);
        return toResponse(inv, List.of(), rates.rates(), Money.today(), names(userId));
    }

    @Transactional
    public InvestmentResponse update(UUID userId, UUID id, InvestmentRequest req) {
        Investment inv = investment(userId, id);
        String name = req.name().trim();
        if (investments.existsByUserIdAndNameIgnoreCaseAndIdNot(userId, name, id)) {
            throw ApiException.conflict("Já existe um investimento chamado \"" + name + "\".");
        }
        if (req.type().byQuantity() != inv.getType().byQuantity() && movements.existsByUserIdAndInvestmentId(userId, id)) {
            throw ApiException.badRequest("Com movimentos registrados, não dá para trocar entre investimento por valor "
                    + "(caixinha, CDB…) e por quantidade (ação, FII, cripto…). Cadastre outro investimento.");
        }
        apply(userId, inv, req, false);
        investments.flush();
        return detail(userId, id).investment();
    }

    /** Exclusão só sem movimentos (com histórico, arquive). */
    @Transactional
    public void delete(UUID userId, UUID id) {
        Investment inv = investment(userId, id);
        if (movements.existsByUserIdAndInvestmentId(userId, id)) {
            throw ApiException.badRequest("Este investimento tem movimentos. Arquive-o em vez de excluir "
                    + "(ou exclua os movimentos antes).");
        }
        investments.delete(inv);
    }

    private void apply(UUID userId, Investment inv, InvestmentRequest req, boolean isNew) {
        InvestmentType type = req.type();
        inv.setName(req.name().trim());
        inv.setType(type);
        String ticker = Money.trimToNull(req.ticker());
        inv.setTicker(ticker == null ? null : ticker.toUpperCase(Locale.ROOT));
        if (type.byQuantity()) {
            inv.setIndexer(null);
            inv.setRate(null);
        } else {
            Indexer indexer = req.indexer();
            if (indexer == null) {
                throw ApiException.badRequest("Escolha como o investimento rende (CDI, Selic, prefixado, IPCA, "
                        + "poupança ou manual).");
            }
            BigDecimal rate = req.rate();
            switch (indexer) {
                case CDI, SELIC -> {
                    if (rate == null) rate = new BigDecimal("100");
                    if (rate.signum() <= 0 || rate.compareTo(new BigDecimal("500")) > 0) {
                        throw ApiException.badRequest("Informe o % do " + indexer + " (ex.: 100 = 100% do " + indexer + ").");
                    }
                }
                case PRE -> {
                    if (rate == null || rate.signum() <= 0 || rate.compareTo(new BigDecimal("100")) > 0) {
                        throw ApiException.badRequest("Informe a taxa prefixada em % ao ano (ex.: 12,5).");
                    }
                }
                case IPCA -> {
                    if (rate == null || rate.compareTo(new BigDecimal("-50")) <= 0 || rate.compareTo(new BigDecimal("100")) > 0) {
                        throw ApiException.badRequest("Informe a taxa acima do IPCA em % ao ano (ex.: 6 para IPCA + 6%).");
                    }
                }
                case POUPANCA, MANUAL -> rate = null;
            }
            inv.setIndexer(indexer);
            inv.setRate(rate);
        }
        if (req.accountId() != null) {
            accounts.findByIdAndUserId(req.accountId(), userId)
                    .orElseThrow(() -> ApiException.notFound("Conta (instituição) não encontrada."));
        }
        inv.setAccountId(req.accountId());
        if (req.walletId() != null) {
            Wallet w = wallets.findByIdAndUserId(req.walletId(), userId)
                    .orElseThrow(() -> ApiException.notFound("Carteira não encontrada."));
            if (w.isArchived() && (isNew || !req.walletId().equals(inv.getWalletId()))) {
                throw ApiException.badRequest("A carteira \"" + w.getName() + "\" está arquivada.");
            }
            if (inv.getAccountId() == null) inv.setAccountId(w.getAccountId());
        }
        inv.setWalletId(req.walletId());
        inv.setMaturityDate(req.maturityDate());
        inv.setColor(Money.trimToNull(req.color()));
        inv.setNotes(Money.trimToNull(req.notes()));
        inv.setArchived(Boolean.TRUE.equals(req.archived()));
    }

    // ---------------------------------------------------------------- movimentos

    @Transactional
    public InvestmentDetail addMovement(UUID userId, UUID investmentId, MovementRequest req, Origin origin) {
        Investment inv = investment(userId, investmentId);
        if (inv.isArchived()) {
            throw ApiException.badRequest("O investimento \"" + inv.getName() + "\" está arquivado.");
        }
        InvestmentMovement m = new InvestmentMovement(userId, investmentId, origin);
        save(userId, inv, m, req, origin);
        return detail(userId, investmentId);
    }

    @Transactional
    public InvestmentDetail updateMovement(UUID userId, UUID investmentId, UUID movementId, MovementRequest req) {
        Investment inv = investment(userId, investmentId);
        InvestmentMovement m = movement(userId, investmentId, movementId);
        save(userId, inv, m, req, m.getOrigin());
        return detail(userId, investmentId);
    }

    /** Exclui um movimento lançado por engano (e o lançamento ligado na carteira). Só pelo site. */
    @Transactional
    public InvestmentDetail deleteMovement(UUID userId, UUID investmentId, UUID movementId) {
        Investment inv = investment(userId, investmentId);
        InvestmentMovement m = movement(userId, investmentId, movementId);
        UUID tx = m.getTransactionId();
        movements.delete(m);
        movements.flush();
        if (tx != null) transactions.deleteInvestmentTransaction(userId, tx);
        // A venda pode ficar sem a compra que a sustentava: confere a posição.
        check(inv, movements.findByUserIdAndInvestmentIdOrderByDateAscCreatedAtAsc(userId, investmentId));
        return detail(userId, investmentId);
    }

    private void save(UUID userId, Investment inv, InvestmentMovement m, MovementRequest req, Origin origin) {
        MovementKind kind = req.kind();
        boolean byQty = inv.getType().byQuantity();
        LocalDate date = req.date();
        if (date.isAfter(Money.today())) {
            throw ApiException.badRequest("A data do movimento não pode ser futura.");
        }
        switch (kind) {
            case APORTE, RESGATE -> {
                if (byQty) throw ApiException.badRequest("Em ações, FIIs e cripto use COMPRA e VENDA (com quantidade e preço).");
            }
            case COMPRA, VENDA -> {
                if (!byQty) throw ApiException.badRequest("Em caixinha, CDB, Tesouro e afins use APORTE e RESGATE (por valor).");
            }
            default -> {
            }
        }

        BigDecimal amount = null, quantity = null, unitPrice = null, fees = BigDecimal.ZERO;
        switch (kind) {
            case APORTE, RESGATE, PROVENTO -> amount = positive(req.amount(), "Informe o valor.");
            case COMPRA, VENDA -> {
                quantity = positive(req.quantity(), "Informe a quantidade.");
                unitPrice = positive(req.unitPrice(), "Informe o preço unitário.");
                fees = req.fees() == null ? BigDecimal.ZERO : Money.of(req.fees());
                if (fees.signum() < 0) throw ApiException.badRequest("As taxas não podem ser negativas.");
                BigDecimal gross = quantity.multiply(unitPrice);
                amount = Money.of(kind == MovementKind.COMPRA ? gross.add(fees) : gross.subtract(fees));
                if (amount.signum() <= 0) throw ApiException.badRequest("O valor da venda menos as taxas precisa ser positivo.");
            }
            case ATUALIZACAO -> {
                if (req.walletId() != null) {
                    throw ApiException.badRequest("Atualização de saldo/cotação não movimenta conta: tire a carteira.");
                }
                if (byQty) {
                    unitPrice = positive(req.unitPrice(), "Informe a cotação atual (preço unitário).");
                } else {
                    if (req.amount() == null || req.amount().signum() < 0) {
                        throw ApiException.badRequest("Informe o saldo atual do investimento.");
                    }
                    amount = Money.of(req.amount());
                }
            }
        }

        if (!Boolean.TRUE.equals(req.allowDuplicate()) && kind != MovementKind.ATUALIZACAO) {
            BigDecimal a = amount, q = quantity;
            movements.findByUserIdAndInvestmentIdOrderByDateAscCreatedAtAsc(userId, inv.getId()).stream()
                    .filter(o -> !o.getId().equals(m.getId()))
                    .filter(o -> o.getKind() == kind)
                    .filter(o -> Math.abs(ChronoUnit.DAYS.between(o.getDate(), date)) <= DUPLICATE_DAYS)
                    .filter(o -> o.getAmount() != null && o.getAmount().compareTo(a) == 0)
                    .filter(o -> q == null || (o.getQuantity() != null && o.getQuantity().compareTo(q) == 0))
                    .findFirst().ifPresent(o -> {
                        throw ApiException.conflict("Já existe " + label(kind) + " de " + brl(o.getAmount()) + " em "
                                + br(o.getDate()) + " neste investimento. Se for outro, confirme para gravar mesmo assim.");
                    });
        }

        UUID oldTx = m.getTransactionId();
        m.setKind(kind);
        m.setDate(date);
        m.setAmount(amount);
        m.setQuantity(quantity);
        m.setUnitPrice(unitPrice);
        m.setFees(fees);
        m.setNotes(Money.trimToNull(req.notes()));
        m.setWalletId(null);
        m.setTransactionId(null);
        m.touch();

        // Confere a posição (ex.: venda maior que a quantidade) antes de gravar.
        List<InvestmentMovement> all = new ArrayList<>(movements.findByUserIdAndInvestmentIdOrderByDateAscCreatedAtAsc(userId, inv.getId()));
        all.removeIf(o -> o.getId() != null && o.getId().equals(m.getId()));
        all.add(m);
        all.sort(Comparator.comparing(InvestmentMovement::getDate).thenComparing(InvestmentMovement::getCreatedAt));
        check(inv, all);

        movements.saveAndFlush(m);
        if (oldTx != null) transactions.deleteInvestmentTransaction(userId, oldTx);
        if (req.walletId() != null) {
            String label = inv.getTicker() != null ? inv.getTicker() : inv.getName();
            String qty = quantity == null ? "" : quantity.stripTrailingZeros().toPlainString().replace('.', ',') + " ";
            TransactionKind txKind;
            String desc;
            switch (kind) {
                case APORTE -> { txKind = TransactionKind.APLICACAO; desc = "Aplicação: " + inv.getName(); }
                case COMPRA -> { txKind = TransactionKind.APLICACAO; desc = "Compra " + qty + label; }
                case RESGATE -> { txKind = TransactionKind.RESGATE; desc = "Resgate: " + inv.getName(); }
                case VENDA -> { txKind = TransactionKind.RESGATE; desc = "Venda " + qty + label; }
                default -> { txKind = TransactionKind.RECEITA; desc = "Proventos: " + label; }
            }
            Transaction t = transactions.saveInvestmentTransaction(userId, origin, txKind, req.walletId(), inv.getId(),
                    date, desc, amount);
            m.setWalletId(req.walletId());
            m.setTransactionId(t.getId());
            movements.saveAndFlush(m);
        }
    }

    private void check(Investment inv, List<InvestmentMovement> list) {
        InvestmentCalculator.value(inv.getType(), inv.getIndexer(), inv.getRate(), list, IndexRates.EMPTY,
                LocalDate.MAX);
    }

    private static BigDecimal positive(BigDecimal v, String message) {
        if (v == null || v.signum() <= 0) throw ApiException.badRequest(message);
        return v;
    }

    // ---------------------------------------------------------------- respostas

    private InvestmentResponse toResponse(Investment i, List<InvestmentMovement> ms, IndexRates r, LocalDate today,
                                          Names n) {
        Valuation v = InvestmentCalculator.value(i.getType(), i.getIndexer(), i.getRate(), ms, r, today);
        BigDecimal gain = v.currentValue().subtract(v.invested());
        BigDecimal pct = v.invested().signum() > 0
                ? gain.multiply(BigDecimal.valueOf(100)).divide(v.invested(), 2, RoundingMode.HALF_EVEN) : null;
        Account a = i.getAccountId() == null ? null : n.accounts().get(i.getAccountId());
        Wallet w = i.getWalletId() == null ? null : n.wallets().get(i.getWalletId());
        return new InvestmentResponse(i.getId(), i.getName(), i.getType(), i.getTicker(), i.getAccountId(),
                a == null ? null : a.getName(), i.getWalletId(), walletLabel(w, n), i.getIndexer(), i.getRate(),
                i.getMaturityDate(), i.getColor(), i.getNotes(), i.isArchived(), i.getType().byQuantity(),
                v.invested(), v.currentValue(), gain, pct, v.quantity(), v.averagePrice(), v.lastPrice(),
                v.lastPriceDate(), v.realizedGain(), v.income(), v.lastUpdate(), v.ratesUntil(), ms.size());
    }

    private MovementResponse toResponse(InvestmentMovement m, Names n) {
        Wallet w = m.getWalletId() == null ? null : n.wallets().get(m.getWalletId());
        return new MovementResponse(m.getId(), m.getInvestmentId(), m.getKind(), m.getDate(), m.getAmount(),
                m.getQuantity() == null ? null : m.getQuantity().stripTrailingZeros(),
                m.getUnitPrice() == null ? null : m.getUnitPrice().stripTrailingZeros(), m.getFees(), m.getWalletId(),
                walletLabel(w, n), m.getTransactionId(), m.getNotes(), m.getOrigin(), m.getCreatedAt());
    }

    private static String walletLabel(Wallet w, Names n) {
        if (w == null) return null;
        Account a = n.accounts().get(w.getAccountId());
        if (a == null) return w.getName();
        long count = n.wallets().values().stream().filter(x -> x.getAccountId().equals(a.getId())).count();
        return count > 1 ? a.getName() + " · " + w.getName() : a.getName();
    }

    record Names(Map<UUID, Account> accounts, Map<UUID, Wallet> wallets) {
    }

    private Names names(UUID userId) {
        return new Names(
                accounts.findByUserIdOrderByNameAsc(userId).stream().collect(Collectors.toMap(Account::getId, Function.identity())),
                wallets.findByUserIdOrderByPositionAscNameAsc(userId).stream().collect(Collectors.toMap(Wallet::getId, Function.identity())));
    }

    Investment investment(UUID userId, UUID id) {
        return investments.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Investimento não encontrado."));
    }

    private InvestmentMovement movement(UUID userId, UUID investmentId, UUID id) {
        return movements.findByIdAndUserIdAndInvestmentId(id, userId, investmentId)
                .orElseThrow(() -> ApiException.notFound("Movimento não encontrado."));
    }

    private static String label(MovementKind k) {
        return switch (k) {
            case APORTE -> "aporte";
            case RESGATE -> "resgate";
            case COMPRA -> "compra";
            case VENDA -> "venda";
            case PROVENTO -> "provento";
            case ATUALIZACAO -> "atualização";
        };
    }

    private static String brl(BigDecimal v) {
        return "R$ " + String.format(Locale.forLanguageTag("pt-BR"), "%,.2f", v);
    }

    private static String br(LocalDate d) {
        return String.format("%02d/%02d/%d", d.getDayOfMonth(), d.getMonthValue(), d.getYear());
    }
}
