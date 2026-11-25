package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.controlefin.audit.AuditService;
import com.controlefin.common.CurrentUser;
import com.controlefin.common.Money;
import com.controlefin.finance.FinanceDtos.AccountResponse;
import com.controlefin.finance.FinanceDtos.CardResponse;
import com.controlefin.finance.FinanceDtos.CategoryResponse;
import com.controlefin.finance.FinanceDtos.CreditLineResponse;
import com.controlefin.finance.FinanceDtos.DashboardResponse;
import com.controlefin.finance.FinanceDtos.InvoiceDetail;
import com.controlefin.finance.FinanceDtos.InvoiceSummary;
import com.controlefin.finance.FinanceDtos.RecurrenceMonth;
import com.controlefin.finance.FinanceDtos.RecurrenceResponse;
import com.controlefin.finance.FinanceDtos.ReverseRequest;
import com.controlefin.finance.FinanceDtos.TransactionFilter;
import com.controlefin.finance.FinanceDtos.TransactionRequest;
import com.controlefin.finance.FinanceDtos.TransactionResponse;
import com.controlefin.finance.FinanceEnums.TransactionKind;
import com.controlefin.finance.FinanceEnums.TransactionStatus;
import com.controlefin.finance.ImportDtos.BatchResponse;
import com.controlefin.finance.ImportDtos.ImportRequest;

import jakarta.validation.Valid;

/**
 * Rotas que a api-externa repassa (Claude/MCP). Só chegam aqui com o segredo de serviço e um token pessoal válido
 * (o usuário vem do token, nunca da requisição). GET exige token de leitura; o resto, token com escrita.
 * Não há nada de login, cadastro ou administração de usuários aqui.
 */
@RestController
@RequestMapping("/api/internal/v1")
public class InternalApiController {

    private final AccountService accounts;
    private final CardService cards;
    private final CategoryService categories;
    private final RecurrenceService recurrences;
    private final TransactionService transactions;
    private final InvoiceService invoices;
    private final DashboardService dashboard;
    private final ImportService imports;
    private final AuditService audit;

    public InternalApiController(AccountService accounts, CardService cards, CategoryService categories,
                                 RecurrenceService recurrences, TransactionService transactions,
                                 InvoiceService invoices, DashboardService dashboard, ImportService imports,
                                 AuditService audit) {
        this.accounts = accounts;
        this.cards = cards;
        this.categories = categories;
        this.recurrences = recurrences;
        this.transactions = transactions;
        this.invoices = invoices;
        this.dashboard = dashboard;
        this.imports = imports;
        this.audit = audit;
    }

    /** Tudo o que o Claude precisa para montar um lote: contas/carteiras, cartões, limites, categorias, recorrências. */
    public record Context(LocalDate today, List<AccountResponse> accounts, List<CreditLineResponse> creditLines,
                          List<CardResponse> cards, List<CategoryResponse> categories,
                          List<RecurrenceResponse> recurrences) {
    }

    @GetMapping("/context")
    public Context context() {
        UUID u = CurrentUser.id();
        return new Context(Money.today(), accounts.list(u, Money.today()), cards.listCreditLines(u), cards.listCards(u),
                categories.list(u), recurrences.list(u));
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return dashboard.dashboard(CurrentUser.id(), month);
    }

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

    /** Checagem de duplicado: use antes de enviar qualquer lançamento. */
    @GetMapping("/transactions/similar")
    public List<TransactionResponse> similar(
            @RequestParam TransactionKind kind,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam BigDecimal amount,
            @RequestParam(required = false) UUID walletId,
            @RequestParam(required = false) UUID cardId) {
        return transactions.similar(CurrentUser.id(), kind, date, amount, walletId, cardId, null);
    }

    @GetMapping("/transactions/{id}")
    public TransactionResponse transaction(@PathVariable UUID id) {
        return transactions.get(CurrentUser.id(), id);
    }

    @PutMapping("/transactions/{id}")
    public TransactionResponse update(@PathVariable UUID id, @Valid @RequestBody TransactionRequest req) {
        UUID u = CurrentUser.id();
        TransactionResponse r = transactions.update(u, id, req);
        audit.log(u, "LANCAMENTO_EDITADO", id, r.description() + " · " + r.amount());
        return r;
    }

    @PostMapping("/transactions/{id}/confirm")
    public TransactionResponse confirm(@PathVariable UUID id) {
        UUID u = CurrentUser.id();
        TransactionResponse r = transactions.confirm(u, id);
        audit.log(u, "LANCAMENTO_CONFIRMADO", id, r.description());
        return r;
    }

    @PostMapping("/transactions/{id}/reverse")
    public TransactionResponse reverse(@PathVariable UUID id, @RequestBody(required = false) ReverseRequest req) {
        UUID u = CurrentUser.id();
        TransactionResponse r = transactions.reverse(u, id, req);
        audit.log(u, "LANCAMENTO_ESTORNADO", id, r.description());
        return r;
    }

    @GetMapping("/recurrences/month")
    public RecurrenceMonth recurrenceMonth(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return recurrences.month(CurrentUser.id(), month == null ? YearMonth.from(Money.today()) : month);
    }

    @GetMapping("/credit-lines/{id}/invoices")
    public List<InvoiceSummary> invoices(@PathVariable UUID id) {
        return invoices.list(CurrentUser.id(), id);
    }

    @GetMapping("/invoices/{id}")
    public InvoiceDetail invoice(@PathVariable UUID id) {
        return transactions.invoice(CurrentUser.id(), id);
    }

    // ---------------------------------------------------------------- importação

    @PostMapping("/imports")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchResponse importBatch(@Valid @RequestBody ImportRequest req) {
        return imports.importBatch(CurrentUser.id(), req);
    }

    @GetMapping("/imports")
    public List<BatchResponse> importList() {
        return imports.list(CurrentUser.id());
    }

    @GetMapping("/imports/{id}")
    public BatchResponse importGet(@PathVariable UUID id) {
        return imports.get(CurrentUser.id(), id);
    }

    @PostMapping("/imports/{id}/confirm")
    public BatchResponse importConfirm(@PathVariable UUID id) {
        return imports.confirm(CurrentUser.id(), id);
    }

    @PostMapping("/imports/{id}/undo")
    public BatchResponse importUndo(@PathVariable UUID id) {
        return imports.undo(CurrentUser.id(), id);
    }
}
