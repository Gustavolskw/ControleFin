package com.controlefin.finance;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.controlefin.common.CurrentUser;
import com.controlefin.common.Money;
import com.controlefin.finance.FinanceDtos.AccountRequest;
import com.controlefin.finance.FinanceDtos.AccountResponse;
import com.controlefin.finance.FinanceDtos.AdjustRequest;
import com.controlefin.finance.FinanceDtos.CategoryRequest;
import com.controlefin.finance.FinanceDtos.CategoryResponse;
import com.controlefin.finance.FinanceDtos.DashboardResponse;
import com.controlefin.finance.FinanceDtos.ReverseRequest;
import com.controlefin.finance.FinanceDtos.TransactionFilter;
import com.controlefin.finance.FinanceDtos.TransactionRequest;
import com.controlefin.finance.FinanceDtos.TransactionResponse;
import com.controlefin.finance.FinanceDtos.WalletRequest;
import com.controlefin.finance.FinanceDtos.WalletUpdateRequest;
import com.controlefin.finance.FinanceEnums.Origin;
import com.controlefin.finance.FinanceEnums.TransactionKind;
import com.controlefin.finance.FinanceEnums.TransactionStatus;

import jakarta.validation.Valid;

/** Endpoints do site para contas, carteiras, categorias, lançamentos e painel. Sempre do usuário logado. */
@RestController
@RequestMapping("/api")
public class FinanceController {

    private final AccountService accounts;
    private final CategoryService categories;
    private final TransactionService transactions;
    private final DashboardService dashboard;

    public FinanceController(AccountService accounts, CategoryService categories, TransactionService transactions,
                             DashboardService dashboard) {
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
        this.dashboard = dashboard;
    }

    // ---------------------------------------------------------------- painel

    @GetMapping("/dashboard")
    public DashboardResponse dashboard(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return dashboard.dashboard(CurrentUser.id(), month);
    }

    // ---------------------------------------------------------------- contas e carteiras

    @GetMapping("/accounts")
    public List<AccountResponse> accounts() {
        return accounts.list(CurrentUser.id(), Money.today());
    }

    @GetMapping("/accounts/{id}")
    public AccountResponse account(@PathVariable UUID id) {
        return accounts.get(CurrentUser.id(), id);
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(@Valid @RequestBody AccountRequest req) {
        return accounts.create(CurrentUser.id(), req);
    }

    @PutMapping("/accounts/{id}")
    public AccountResponse updateAccount(@PathVariable UUID id, @Valid @RequestBody AccountRequest req) {
        return accounts.update(CurrentUser.id(), id, req);
    }

    @DeleteMapping("/accounts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@PathVariable UUID id) {
        accounts.delete(CurrentUser.id(), id);
    }

    @PostMapping("/accounts/{id}/wallets")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse addWallet(@PathVariable UUID id, @Valid @RequestBody WalletRequest req) {
        return accounts.addWallet(CurrentUser.id(), id, req);
    }

    @PutMapping("/wallets/{id}")
    public AccountResponse updateWallet(@PathVariable UUID id, @Valid @RequestBody WalletUpdateRequest req) {
        return accounts.updateWallet(CurrentUser.id(), id, req);
    }

    @DeleteMapping("/wallets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWallet(@PathVariable UUID id) {
        accounts.deleteWallet(CurrentUser.id(), id);
    }

    @PostMapping("/wallets/{id}/adjust")
    public AccountResponse adjust(@PathVariable UUID id, @Valid @RequestBody AdjustRequest req) {
        return accounts.adjust(CurrentUser.id(), id, req);
    }

    // ---------------------------------------------------------------- categorias

    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return categories.list(CurrentUser.id());
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse createCategory(@Valid @RequestBody CategoryRequest req) {
        return categories.create(CurrentUser.id(), req);
    }

    @PutMapping("/categories/{id}")
    public CategoryResponse updateCategory(@PathVariable UUID id, @Valid @RequestBody CategoryRequest req) {
        return categories.update(CurrentUser.id(), id, req);
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCategory(@PathVariable UUID id) {
        categories.delete(CurrentUser.id(), id);
    }

    // ---------------------------------------------------------------- lançamentos

    @GetMapping("/transactions")
    public List<TransactionResponse> transactions(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) UUID walletId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) TransactionKind kind,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID cardId,
            @RequestParam(required = false) UUID creditLineId) {
        return transactions.list(CurrentUser.id(),
                new TransactionFilter(from, to, accountId, walletId, categoryId, kind, status, q, cardId, creditLineId));
    }

    @GetMapping("/transactions/{id}")
    public TransactionResponse transaction(@PathVariable UUID id) {
        return transactions.get(CurrentUser.id(), id);
    }

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse createTransaction(@Valid @RequestBody TransactionRequest req) {
        return transactions.create(CurrentUser.id(), req, Origin.WEB);
    }

    @PutMapping("/transactions/{id}")
    public TransactionResponse updateTransaction(@PathVariable UUID id, @Valid @RequestBody TransactionRequest req) {
        return transactions.update(CurrentUser.id(), id, req);
    }

    /** Possíveis duplicados: mesmo tipo e valor, até 3 dias de diferença, mesma carteira/cartão. */
    @GetMapping("/transactions/similar")
    public List<TransactionResponse> similarTransactions(
            @RequestParam TransactionKind kind,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam java.math.BigDecimal amount,
            @RequestParam(required = false) UUID walletId,
            @RequestParam(required = false) UUID cardId,
            @RequestParam(required = false) UUID excludeId) {
        return transactions.similar(CurrentUser.id(), kind, date, amount, walletId, cardId, excludeId);
    }

    /** Estorna/cancela uma compra (nunca apaga). */
    @PostMapping("/transactions/{id}/reverse")
    public TransactionResponse reverseTransaction(@PathVariable UUID id, @RequestBody(required = false) ReverseRequest req) {
        return transactions.reverse(CurrentUser.id(), id, req);
    }

    @DeleteMapping("/transactions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTransaction(@PathVariable UUID id) {
        transactions.delete(CurrentUser.id(), id);
    }
}
