package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.controlefin.common.Money;
import com.controlefin.finance.FinanceDtos.AccountResponse;
import com.controlefin.finance.FinanceDtos.CategoryTotal;
import com.controlefin.finance.FinanceDtos.DashboardResponse;
import com.controlefin.finance.FinanceDtos.MonthSummary;
import com.controlefin.finance.FinanceEnums.Holder;
import com.controlefin.finance.FinanceEnums.TransactionKind;
import com.controlefin.finance.FinanceEnums.TransactionStatus;

/** Painel: saldo total de todas as contas (PF + PJ + benefícios), por conta/carteira e resumo do mês. */
@Service
public class DashboardService {

    private final AccountService accountService;
    private final TransactionRepository transactions;
    private final CategoryRepository categories;
    private final CardService cardService;
    private final RecurrenceService recurrenceService;
    private final InvestmentService investmentService;

    public DashboardService(AccountService accountService, TransactionRepository transactions,
                            CategoryRepository categories, CardService cardService,
                            RecurrenceService recurrenceService, InvestmentService investmentService) {
        this.recurrenceService = recurrenceService;
        this.investmentService = investmentService;
        this.accountService = accountService;
        this.transactions = transactions;
        this.categories = categories;
        this.cardService = cardService;
    }

    /** Lê e, se faltar, cria a fatura aberta de cada cartão (por isso não é somente leitura). */
    @Transactional
    public DashboardResponse dashboard(UUID userId, YearMonth month) {
        LocalDate today = Money.today();
        YearMonth ym = month == null ? YearMonth.from(today) : month;

        List<AccountResponse> accounts = accountService.list(userId, today);
        BigDecimal total = Money.nz(null);
        BigDecimal pf = Money.nz(null);
        BigDecimal pj = Money.nz(null);
        for (AccountResponse a : accounts) {
            total = total.add(a.balance());
            if (a.holder() == Holder.PJ) pj = pj.add(a.balance()); else pf = pf.add(a.balance());
        }
        LocalDate endOfMonth = YearMonth.from(today).atEndOfMonth();
        BigDecimal projected = accountService.balances(userId, endOfMonth).values().stream()
                .reduce(Money.nz(null), BigDecimal::add)
                .add(recurrenceService.pendingWalletImpact(userId, YearMonth.from(today)));

        var lines = cardService.listCreditLines(userId).stream().filter(l -> !l.archived()).toList();
        BigDecimal invested = investmentService.totalValue(userId);
        return new DashboardResponse(today, total, pf, pj, projected, accounts, summary(userId, ym),
                transactions.countByUserIdAndStatus(userId, TransactionStatus.PENDENTE), CardService.toPay(lines),
                lines, recurrenceService.upcoming(userId, 30, 8), invested, total.add(invested));
    }

    MonthSummary summary(UUID userId, YearMonth ym) {
        Map<UUID, Category> byId = categories.findByUserIdOrderByNameAsc(userId).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        Map<UUID, BigDecimal> expenses = new HashMap<>();
        Map<UUID, BigDecimal> income = new HashMap<>();
        BigDecimal totalIncome = Money.nz(null);
        BigDecimal totalExpense = Money.nz(null);
        for (var row : transactions.totalsByCategory(userId, ym.atDay(1), ym.atEndOfMonth())) {
            // Subcategoria soma na categoria pai (visão do painel).
            UUID key = row.getCategoryId();
            Category c = key == null ? null : byId.get(key);
            if (c != null && c.getParentId() != null) key = c.getParentId();
            BigDecimal value = Money.nz(row.getTotal());
            if (row.getKind() == TransactionKind.RECEITA) {
                income.merge(key, value, BigDecimal::add);
                totalIncome = totalIncome.add(value);
            } else if (row.getKind() == TransactionKind.ESTORNO) {
                // Estorno abate a despesa da categoria.
                expenses.merge(key, value.negate(), BigDecimal::add);
                totalExpense = totalExpense.subtract(value);
            } else {
                expenses.merge(key, value, BigDecimal::add);
                totalExpense = totalExpense.add(value);
            }
        }
        return new MonthSummary(ym.toString(), totalIncome, totalExpense, totalIncome.subtract(totalExpense),
                totals(expenses, byId), totals(income, byId));
    }

    private static List<CategoryTotal> totals(Map<UUID, BigDecimal> map, Map<UUID, Category> byId) {
        List<CategoryTotal> list = new ArrayList<>();
        map.forEach((id, amount) -> {
            Category c = id == null ? null : byId.get(id);
            list.add(new CategoryTotal(id, c == null ? "Sem categoria" : c.getName(), amount));
        });
        list.removeIf(c -> c.amount().signum() == 0);
        list.sort(Comparator.comparing(CategoryTotal::amount).reversed());
        return list;
    }
}
