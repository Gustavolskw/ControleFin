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
import com.controlefin.finance.FinanceDtos.AccountRequest;
import com.controlefin.finance.FinanceDtos.AccountResponse;
import com.controlefin.finance.FinanceDtos.AdjustRequest;
import com.controlefin.finance.FinanceDtos.CardRequest;
import com.controlefin.finance.FinanceDtos.CardResponse;
import com.controlefin.finance.FinanceDtos.CategoryRequest;
import com.controlefin.finance.FinanceDtos.CategoryResponse;
import com.controlefin.finance.FinanceDtos.CreditLineRequest;
import com.controlefin.finance.FinanceDtos.CreditLineResponse;
import com.controlefin.finance.FinanceDtos.RecurrenceLinkRequest;
import com.controlefin.finance.FinanceDtos.RecurrenceRequest;
import com.controlefin.finance.FinanceDtos.WalletRequest;
import com.controlefin.finance.FinanceDtos.WalletUpdateRequest;
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
import com.controlefin.finance.InvestmentDtos.InvestmentDetail;
import com.controlefin.finance.InvestmentDtos.InvestmentRequest;
import com.controlefin.finance.InvestmentDtos.InvestmentResponse;
import com.controlefin.finance.InvestmentDtos.MovementRequest;
import com.controlefin.finance.InvestmentDtos.Portfolio;
import com.controlefin.finance.FinanceEnums.Origin;

import jakarta.validation.Valid;

/**
 * Rotas que a api-externa repassa (Claude/MCP). Só chegam aqui com o segredo de serviço e um token pessoal válido
 * (o usuário vem do token, nunca da requisição). GET exige token de leitura; o resto, token com escrita.
 * Não há nada de login, cadastro ou administração de usuários aqui: só a gestão financeira (lançamentos, importações e os
 * registros auxiliares: contas, carteiras, categorias, limites, cartões, recorrências e investimentos). Não há exclusão:
 * para tirar de uso, edite com archived=true (recorrência: active=false).
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
    private final InvestmentService investments;

    public InternalApiController(AccountService accounts, CardService cards, CategoryService categories,
                                 RecurrenceService recurrences, TransactionService transactions,
                                 InvoiceService invoices, DashboardService dashboard, ImportService imports,
                                 AuditService audit, InvestmentService investments) {
        this.investments = investments;
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

    @PostMapping("/imports/{id}/attachments")
    public BatchResponse importAttachments(@PathVariable UUID id, @Valid @RequestBody ImportDtos.AttachmentsRequest req) {
        return imports.addAttachments(CurrentUser.id(), id, req);
    }

    @PostMapping("/imports/{id}/confirm")
    public BatchResponse importConfirm(@PathVariable UUID id) {
        return imports.confirm(CurrentUser.id(), id);
    }

    @PostMapping("/imports/{id}/undo")
    public BatchResponse importUndo(@PathVariable UUID id) {
        return imports.undo(CurrentUser.id(), id);
    }

    // ---------------------------------------------------------------- contas e carteiras

    @GetMapping("/accounts")
    public List<AccountResponse> accountList() {
        return accounts.list(CurrentUser.id(), Money.today());
    }

    @GetMapping("/accounts/{id}")
    public AccountResponse account(@PathVariable UUID id) {
        return accounts.get(CurrentUser.id(), id);
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse createAccount(@Valid @RequestBody AccountRequest req) {
        UUID u = CurrentUser.id();
        AccountResponse r = accounts.create(u, req);
        audit.log(u, "CONTA_CRIADA", r.id(), r.name());
        return r;
    }

    @PutMapping("/accounts/{id}")
    public AccountResponse updateAccount(@PathVariable UUID id, @Valid @RequestBody AccountRequest req) {
        UUID u = CurrentUser.id();
        AccountResponse r = accounts.update(u, id, req);
        audit.log(u, "CONTA_EDITADA", id, r.name());
        return r;
    }

    @PostMapping("/accounts/{id}/wallets")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse addWallet(@PathVariable UUID id, @Valid @RequestBody WalletRequest req) {
        UUID u = CurrentUser.id();
        AccountResponse r = accounts.addWallet(u, id, req);
        audit.log(u, "CARTEIRA_CRIADA", id, r.name() + " · " + req.name());
        return r;
    }

    @PutMapping("/wallets/{id}")
    public AccountResponse updateWallet(@PathVariable UUID id, @Valid @RequestBody WalletUpdateRequest req) {
        UUID u = CurrentUser.id();
        AccountResponse r = accounts.updateWallet(u, id, req);
        audit.log(u, "CARTEIRA_EDITADA", id, r.name() + " · " + req.name());
        return r;
    }

    /** Informa o saldo real: lança um AJUSTE com a diferença. */
    @PostMapping("/wallets/{id}/adjust")
    public AccountResponse adjustWallet(@PathVariable UUID id, @Valid @RequestBody AdjustRequest req) {
        UUID u = CurrentUser.id();
        AccountResponse r = accounts.adjust(u, id, req);
        audit.log(u, "SALDO_AJUSTADO", id, r.name() + " · " + req.balance());
        return r;
    }

    // ---------------------------------------------------------------- categorias

    @GetMapping("/categories")
    public List<CategoryResponse> categoryList() {
        return categories.list(CurrentUser.id());
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse createCategory(@Valid @RequestBody CategoryRequest req) {
        UUID u = CurrentUser.id();
        CategoryResponse r = categories.create(u, req);
        audit.log(u, "CATEGORIA_CRIADA", r.id(), r.name());
        return r;
    }

    @PutMapping("/categories/{id}")
    public CategoryResponse updateCategory(@PathVariable UUID id, @Valid @RequestBody CategoryRequest req) {
        UUID u = CurrentUser.id();
        CategoryResponse r = categories.update(u, id, req);
        audit.log(u, "CATEGORIA_EDITADA", id, r.name());
        return r;
    }

    // ---------------------------------------------------------------- limites de crédito e cartões

    @GetMapping("/credit-lines")
    public List<CreditLineResponse> creditLineList() {
        return cards.listCreditLines(CurrentUser.id());
    }

    @GetMapping("/credit-lines/{id}")
    public CreditLineResponse creditLine(@PathVariable UUID id) {
        return cards.getCreditLine(CurrentUser.id(), id);
    }

    @PostMapping("/credit-lines")
    @ResponseStatus(HttpStatus.CREATED)
    public CreditLineResponse createCreditLine(@Valid @RequestBody CreditLineRequest req) {
        UUID u = CurrentUser.id();
        CreditLineResponse r = cards.createCreditLine(u, req);
        audit.log(u, "LIMITE_CRIADO", r.id(), r.name() + " · " + r.limit());
        return r;
    }

    @PutMapping("/credit-lines/{id}")
    public CreditLineResponse updateCreditLine(@PathVariable UUID id, @Valid @RequestBody CreditLineRequest req) {
        UUID u = CurrentUser.id();
        CreditLineResponse r = cards.updateCreditLine(u, id, req);
        audit.log(u, "LIMITE_EDITADO", id, r.name() + " · " + r.limit());
        return r;
    }

    @GetMapping("/cards")
    public List<CardResponse> cardList() {
        return cards.listCards(CurrentUser.id());
    }

    @PostMapping("/cards")
    @ResponseStatus(HttpStatus.CREATED)
    public CardResponse createCard(@Valid @RequestBody CardRequest req) {
        UUID u = CurrentUser.id();
        CardResponse r = cards.createCard(u, req);
        audit.log(u, "CARTAO_CRIADO", r.id(), r.name());
        return r;
    }

    @PutMapping("/cards/{id}")
    public CardResponse updateCard(@PathVariable UUID id, @Valid @RequestBody CardRequest req) {
        UUID u = CurrentUser.id();
        CardResponse r = cards.updateCard(u, id, req);
        audit.log(u, "CARTAO_EDITADO", id, r.name());
        return r;
    }

    // ---------------------------------------------------------------- recorrências

    @GetMapping("/recurrences")
    public List<RecurrenceResponse> recurrenceList() {
        return recurrences.list(CurrentUser.id());
    }

    @PostMapping("/recurrences")
    @ResponseStatus(HttpStatus.CREATED)
    public RecurrenceResponse createRecurrence(@Valid @RequestBody RecurrenceRequest req) {
        UUID u = CurrentUser.id();
        RecurrenceResponse r = recurrences.create(u, req);
        audit.log(u, "RECORRENCIA_CRIADA", r.id(), r.description() + " · " + r.amount());
        return r;
    }

    @PutMapping("/recurrences/{id}")
    public RecurrenceResponse updateRecurrence(@PathVariable UUID id, @Valid @RequestBody RecurrenceRequest req) {
        UUID u = CurrentUser.id();
        RecurrenceResponse r = recurrences.update(u, id, req);
        audit.log(u, "RECORRENCIA_EDITADA", id, r.description() + " · " + r.amount());
        return r;
    }

    /** Pula uma ocorrência (ex.: assinatura suspensa naquele mês). */
    @PostMapping("/recurrences/{id}/occurrences/{date}/skip")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void skipOccurrence(@PathVariable UUID id,
                               @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        UUID u = CurrentUser.id();
        recurrences.skip(u, id, date);
        audit.log(u, "OCORRENCIA_PULADA", id, date.toString());
    }

    /** Volta a prever uma ocorrência pulada. */
    @PostMapping("/recurrences/{id}/occurrences/{date}/unskip")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unskipOccurrence(@PathVariable UUID id,
                                 @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        UUID u = CurrentUser.id();
        recurrences.unskip(u, id, date);
        audit.log(u, "OCORRENCIA_RETOMADA", id, date.toString());
    }

    /** Lançamentos parecidos ainda sem vínculo com a ocorrência (possível duplicado: vincule em vez de lançar). */
    @GetMapping("/recurrences/{id}/occurrences/{date}/candidates")
    public List<TransactionResponse> occurrenceCandidates(@PathVariable UUID id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return recurrences.candidates(CurrentUser.id(), id, date);
    }

    /** Liga um lançamento existente a uma ocorrência (campos nulos desligam). */
    @PutMapping("/transactions/{id}/recurrence")
    public TransactionResponse linkRecurrence(@PathVariable UUID id,
                                              @RequestBody(required = false) RecurrenceLinkRequest req) {
        UUID u = CurrentUser.id();
        TransactionResponse r = recurrences.link(u, id, req);
        audit.log(u, "LANCAMENTO_VINCULADO", id, r.description());
        return r;
    }

    // ---------------------------------------------------------------- investimentos

    @GetMapping("/investments")
    public Portfolio investmentPortfolio() {
        return investments.portfolio(CurrentUser.id());
    }

    @GetMapping("/investments/{id}")
    public InvestmentDetail investment(@PathVariable UUID id) {
        return investments.detail(CurrentUser.id(), id);
    }

    @PostMapping("/investments")
    @ResponseStatus(HttpStatus.CREATED)
    public InvestmentResponse createInvestment(@Valid @RequestBody InvestmentRequest req) {
        UUID u = CurrentUser.id();
        InvestmentResponse r = investments.create(u, req);
        audit.log(u, "INVESTIMENTO_CRIADO", r.id(), r.name());
        return r;
    }

    @PutMapping("/investments/{id}")
    public InvestmentResponse updateInvestment(@PathVariable UUID id, @Valid @RequestBody InvestmentRequest req) {
        UUID u = CurrentUser.id();
        InvestmentResponse r = investments.update(u, id, req);
        audit.log(u, "INVESTIMENTO_EDITADO", id, r.name());
        return r;
    }

    @PostMapping("/investments/{id}/movements")
    @ResponseStatus(HttpStatus.CREATED)
    public InvestmentDetail addInvestmentMovement(@PathVariable UUID id, @Valid @RequestBody MovementRequest req) {
        UUID u = CurrentUser.id();
        InvestmentDetail d = investments.addMovement(u, id, req, Origin.API);
        audit.log(u, "MOVIMENTO_INVESTIMENTO", id, d.investment().name() + " · " + req.kind()
                + (req.amount() != null ? " · " + req.amount() : ""));
        return d;
    }

    @PutMapping("/investments/{id}/movements/{movementId}")
    public InvestmentDetail updateInvestmentMovement(@PathVariable UUID id, @PathVariable UUID movementId,
                                                     @Valid @RequestBody MovementRequest req) {
        UUID u = CurrentUser.id();
        InvestmentDetail d = investments.updateMovement(u, id, movementId, req);
        audit.log(u, "MOVIMENTO_INVESTIMENTO_EDITADO", movementId, d.investment().name() + " · " + req.kind());
        return d;
    }
}
