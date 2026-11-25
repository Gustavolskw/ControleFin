package com.controlefin.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.controlefin.common.ApiException;
import com.controlefin.common.Money;
import com.controlefin.finance.FinanceDtos.InvoiceDetail;
import com.controlefin.finance.FinanceDtos.ReverseRequest;
import com.controlefin.finance.FinanceDtos.TransactionFilter;
import com.controlefin.finance.FinanceDtos.TransactionRequest;
import com.controlefin.finance.FinanceDtos.TransactionResponse;
import com.controlefin.finance.FinanceEnums.CategoryKind;
import com.controlefin.finance.FinanceEnums.Origin;
import com.controlefin.finance.FinanceEnums.TransactionKind;
import com.controlefin.finance.FinanceEnums.TransactionStatus;

/**
 * Lançamentos em carteiras e em faturas de cartão. Regras validadas aqui e reforçadas por CHECKs no banco.
 *
 * <ul>
 *   <li>DESPESA no crédito: sem carteira, entra na fatura escolhida pela data (ou informada). Parcelada: uma linha
 *       por parcela, cada uma na fatura seguinte; o centavo que sobra da divisão vai na 1ª parcela.</li>
 *   <li>DESPESA no débito/voucher: sai da carteira ligada ao cartão.</li>
 *   <li>PAGAMENTO_FATURA: sai da carteira e abate a fatura; não é despesa.</li>
 *   <li>Estornar ({@link #reverse}) nunca apaga: parcelas em faturas ainda abertas são canceladas; o que já foi
 *       cobrado em fatura fechada volta como ESTORNO na fatura aberta (ou na carteira, no débito).</li>
 * </ul>
 */
@Service
public class TransactionService {

    static final int MAX_RESULTS = 1000;

    private final TransactionRepository transactions;
    private final WalletRepository wallets;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final CardRepository cards;
    private final CreditLineRepository creditLines;
    private final InvoiceRepository invoices;
    private final InvoiceService invoiceService;
    private final RecurrenceRepository recurrences;

    public TransactionService(TransactionRepository transactions, WalletRepository wallets,
                              AccountRepository accounts, CategoryRepository categories, CardRepository cards,
                              CreditLineRepository creditLines, InvoiceRepository invoices,
                              InvoiceService invoiceService, RecurrenceRepository recurrences) {
        this.recurrences = recurrences;
        this.transactions = transactions;
        this.wallets = wallets;
        this.accounts = accounts;
        this.categories = categories;
        this.cards = cards;
        this.creditLines = creditLines;
        this.invoices = invoices;
        this.invoiceService = invoiceService;
    }

    // ---------------------------------------------------------------- consulta

    @Transactional(readOnly = true)
    public List<TransactionResponse> list(UUID userId, TransactionFilter f) {
        LocalDate today = Money.today();
        LocalDate from = f.from() != null ? f.from() : today.withDayOfMonth(1);
        LocalDate to = f.to() != null ? f.to() : today.withDayOfMonth(today.lengthOfMonth());
        if (to.isBefore(from)) {
            throw ApiException.badRequest("A data final é anterior à inicial.");
        }

        Specification<Transaction> spec = (root, q, cb) -> cb.and(
                cb.equal(root.get("userId"), userId),
                cb.between(root.get("date"), from, to));
        if (f.walletId() != null) {
            spec = spec.and((root, q, cb) -> cb.or(cb.equal(root.get("walletId"), f.walletId()),
                    cb.equal(root.get("targetWalletId"), f.walletId())));
        } else if (f.accountId() != null) {
            List<UUID> ids = wallets.findByUserIdAndAccountIdOrderByPositionAscNameAsc(userId, f.accountId())
                    .stream().map(Wallet::getId).toList();
            if (ids.isEmpty()) return List.of();
            spec = spec.and((root, q, cb) -> cb.or(root.get("walletId").in(ids), root.get("targetWalletId").in(ids)));
        }
        if (f.cardId() != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("cardId"), f.cardId()));
        }
        if (f.creditLineId() != null) {
            List<UUID> ids = invoices.findByUserIdAndCreditLineIdOrderByReferenceMonthAsc(userId, f.creditLineId())
                    .stream().map(Invoice::getId).toList();
            if (ids.isEmpty()) return List.of();
            spec = spec.and((root, q, cb) -> root.get("invoiceId").in(ids));
        }
        if (f.categoryId() != null) {
            // A categoria pai inclui as subcategorias.
            List<UUID> ids = new ArrayList<>();
            ids.add(f.categoryId());
            categories.findByUserIdOrderByNameAsc(userId).stream()
                    .filter(c -> f.categoryId().equals(c.getParentId())).forEach(c -> ids.add(c.getId()));
            spec = spec.and((root, q, cb) -> root.get("categoryId").in(ids));
        }
        if (f.kind() != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("kind"), f.kind()));
        }
        if (f.status() != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), f.status()));
        }
        String text = Money.trimToNull(f.q());
        if (text != null) {
            String like = "%" + text.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, q, cb) -> cb.or(cb.like(cb.lower(root.get("description")), like, '\\'),
                    cb.like(cb.lower(root.get("notes")), like, '\\')));
        }

        var page = PageRequest.of(0, MAX_RESULTS, Sort.by(Sort.Order.desc("date"), Sort.Order.desc("createdAt")));
        List<Transaction> items = transactions.findAll(spec, page).getContent();
        Names names = names(userId);
        return items.stream().map(t -> toResponse(t, names)).toList();
    }

    @Transactional(readOnly = true)
    public TransactionResponse get(UUID userId, UUID id) {
        return toResponse(transaction(userId, id), names(userId));
    }

    /** Fatura com todas as compras, estornos e pagamentos. */
    @Transactional(readOnly = true)
    public InvoiceDetail invoice(UUID userId, UUID invoiceId) {
        Invoice inv = invoiceService.invoice(userId, invoiceId);
        Names names = names(userId);
        List<TransactionResponse> items = transactions.findByUserIdAndInvoiceIdOrderByDateAscCreatedAtAsc(userId, invoiceId)
                .stream().map(t -> toResponse(t, names)).toList();
        return invoiceService.detail(userId, inv, items);
    }

    // ---------------------------------------------------------------- gravação

    /** Cria o lançamento. Compra parcelada cria uma linha por parcela e devolve a 1ª. */
    @Transactional
    public TransactionResponse create(UUID userId, TransactionRequest req, Origin origin) {
        Plan p = plan(userId, req, null);
        Recurrence recurrence = null;
        if (req.recurrenceId() != null) {
            recurrence = recurrences.findByIdAndUserId(req.recurrenceId(), userId)
                    .orElseThrow(() -> ApiException.notFound("Recorrência não encontrada."));
            RecurrenceService.checkOccurrence(transactions, userId, recurrence, req.recurrenceDate(), req.kind(),
                    p.installments() > 1, null);
        } else if (req.recurrenceDate() != null) {
            throw ApiException.badRequest("Informe a recorrência da ocorrência.");
        }
        List<Transaction> rows = new ArrayList<>();
        if (p.installments() == 1) {
            Transaction t = new Transaction(userId, origin);
            fill(t, req, p, p.amount(), req.date(), p.invoice());
            if (recurrence != null) {
                t.setRecurrence(recurrence.getId(), req.recurrenceDate());
                recurrences.unskip(recurrence.getId(), req.recurrenceDate());
            }
            rows.add(t);
        } else {
            UUID group = UUID.randomUUID();
            BigDecimal part = p.amount().divide(BigDecimal.valueOf(p.installments()), 2, RoundingMode.DOWN);
            BigDecimal first = p.amount().subtract(part.multiply(BigDecimal.valueOf(p.installments() - 1)));
            Invoice inv = p.invoice();
            for (int k = 1; k <= p.installments(); k++) {
                if (k > 1) inv = invoiceService.next(p.line(), inv);
                Transaction t = new Transaction(userId, origin);
                fill(t, req, p, k == 1 ? first : part, req.date().plusMonths(k - 1), inv);
                t.setInstallment(group, k, p.installments());
                rows.add(t);
            }
        }
        transactions.saveAllAndFlush(rows);
        return toResponse(rows.get(0), names(userId));
    }

    /**
     * Edita. Em compra parcelada só descrição, categoria, observações, situação e "reserva limite" mudam (em todas
     * as parcelas); para mudar valor, data, cartão ou parcelas, estorne/exclua e lance de novo.
     */
    @Transactional
    public TransactionResponse update(UUID userId, UUID id, TransactionRequest req) {
        Transaction t = transaction(userId, id);
        if (t.getInstallmentGroup() != null) {
            if (req.kind() != t.getKind()) {
                throw ApiException.badRequest("Não dá para mudar o tipo de uma compra parcelada.");
            }
            UUID categoryId = category(userId, req.kind(), req.categoryId());
            for (Transaction row : group(userId, t)) {
                row.setDescription(req.description().trim());
                row.setCategoryId(categoryId);
                row.setNotes(Money.trimToNull(req.notes()));
                row.setStatus(req.status() == null ? TransactionStatus.CONFIRMADO : req.status());
                if (req.consumesLimit() != null) row.setConsumesLimit(req.consumesLimit());
                row.touch();
            }
            transactions.flush();
            return toResponse(t, names(userId));
        }
        if (req.installments() != null && req.installments() > 1) {
            throw ApiException.badRequest("Para parcelar uma compra já lançada, exclua-a e lance de novo parcelada.");
        }
        Plan p = plan(userId, req, t);
        fill(t, req, p, p.amount(), req.date(), p.invoice());
        t.touch();
        transactions.flush();
        return toResponse(t, names(userId));
    }

    /** Exclui (compra parcelada: todas as parcelas). Para desfazer uma compra real, prefira {@link #reverse}. */
    @Transactional
    public void delete(UUID userId, UUID id) {
        Transaction t = transaction(userId, id);
        transactions.deleteAll(t.getInstallmentGroup() == null ? List.of(t) : group(userId, t));
    }

    /**
     * Estorna/cancela uma compra sem apagar nada:
     * <ul>
     *   <li>crédito: parcelas em faturas que ainda não fecharam (ou pendentes) viram CANCELADO; o que já foi cobrado
     *       em fatura fechada volta como ESTORNO na fatura aberta;</li>
     *   <li>carteira/débito: pendente ou com data futura vira CANCELADO; senão entra um ESTORNO na carteira.</li>
     * </ul>
     */
    @Transactional
    public TransactionResponse reverse(UUID userId, UUID id, ReverseRequest req) {
        Transaction t = transaction(userId, id);
        if (t.getKind() != TransactionKind.DESPESA) {
            throw ApiException.badRequest("Só compras (despesas) podem ser estornadas.");
        }
        List<Transaction> rows = t.getInstallmentGroup() == null ? List.of(t) : group(userId, t);
        if (transactions.existsByUserIdAndReversalOfIn(userId, rows.stream().map(Transaction::getId).toList())) {
            throw ApiException.conflict("Esta compra já foi estornada.");
        }
        if (rows.stream().allMatch(r -> r.getStatus() == TransactionStatus.CANCELADO)) {
            throw ApiException.badRequest("Esta compra já está cancelada.");
        }
        LocalDate today = Money.today();
        LocalDate date = req == null || req.date() == null ? today : req.date();

        BigDecimal charged = Money.nz(null);
        for (Transaction r : rows) {
            if (r.getStatus() == TransactionStatus.CANCELADO) continue;
            boolean alreadyCharged;
            if (r.isCredit()) {
                Invoice inv = invoiceService.invoice(userId, r.getInvoiceId());
                alreadyCharged = r.getStatus() == TransactionStatus.CONFIRMADO && !inv.getClosingDate().isAfter(today);
            } else {
                alreadyCharged = r.getStatus() == TransactionStatus.CONFIRMADO && !r.getDate().isAfter(today);
            }
            if (alreadyCharged) {
                charged = charged.add(r.getAmount());
            } else {
                r.setStatus(TransactionStatus.CANCELADO);
                r.touch();
            }
        }

        Transaction result = t;
        if (charged.signum() > 0) {
            Transaction e = new Transaction(userId, t.getOrigin());
            e.setKind(TransactionKind.ESTORNO);
            e.setStatus(TransactionStatus.CONFIRMADO);
            e.setDate(date);
            String desc = "Estorno: " + t.getDescription();
            e.setDescription(desc.length() > 200 ? desc.substring(0, 200) : desc);
            e.setAmount(charged);
            e.setCategoryId(t.getCategoryId());
            e.setCardId(t.getCardId());
            e.setReversalOf(t.getId());
            if (t.isCredit()) {
                Invoice original = invoiceService.invoice(userId, t.getInvoiceId());
                CreditLine line = creditLines.findByIdAndUserId(original.getCreditLineId(), userId).orElseThrow();
                e.setInvoiceId(invoiceService.invoiceFor(line, date).getId());
            } else {
                e.setWalletId(t.getWalletId());
            }
            transactions.save(e);
            result = e;
        }
        transactions.flush();
        return toResponse(result, names(userId));
    }

    /**
     * Possíveis duplicados de um lançamento novo: mesmo tipo e mesmo valor, até 3 dias de diferença e, se informados,
     * na mesma carteira ou cartão. Use antes de gravar (o Claude também deve usar antes de importar).
     */
    @Transactional(readOnly = true)
    public List<TransactionResponse> similar(UUID userId, TransactionKind kind, LocalDate date, BigDecimal amount,
                                             UUID walletId, UUID cardId, UUID excludeId) {
        BigDecimal value = Money.of(amount);
        Names names = names(userId);
        return transactions.sameKindBetween(userId, kind, date.minusDays(3), date.plusDays(3)).stream()
                .filter(t -> excludeId == null || !excludeId.equals(t.getId()))
                .filter(t -> t.getAmount().compareTo(value) == 0)
                .filter(t -> (walletId == null && cardId == null)
                        || (cardId != null && cardId.equals(t.getCardId()))
                        || (walletId != null && walletId.equals(t.getWalletId())))
                .limit(10).map(t -> toResponse(t, names)).toList();
    }

    Transaction transaction(UUID userId, UUID id) {
        return transactions.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Lançamento não encontrado."));
    }

    private List<Transaction> group(UUID userId, Transaction t) {
        return transactions.findByUserIdAndInstallmentGroupOrderByInstallmentNumberAsc(userId, t.getInstallmentGroup());
    }

    // ---------------------------------------------------------------- validação

    /** Resultado da validação: onde o lançamento cai e com que valor. */
    record Plan(BigDecimal amount, Wallet wallet, UUID targetWalletId, UUID categoryId, Card card, CreditLine line,
                Invoice invoice, int installments, boolean consumesLimit, Foreign foreign) {
    }

    record Foreign(String currency, BigDecimal originalAmount, BigDecimal rate, BigDecimal iof) {
    }

    /** {@code current} = lançamento sendo editado (null ao criar). */
    private Plan plan(UUID userId, TransactionRequest req, Transaction current) {
        TransactionKind kind = req.kind();
        boolean isNew = current == null;

        Foreign foreign = foreign(req);
        BigDecimal amount = req.amount() != null ? Money.of(req.amount())
                : foreign != null ? Money.of(foreign.originalAmount().multiply(foreign.rate()))
                        .add(foreign.iof() == null ? BigDecimal.ZERO : foreign.iof())
                : null;
        if (amount == null || amount.signum() == 0) {
            throw ApiException.badRequest("Informe um valor diferente de zero.");
        }
        if (kind != TransactionKind.AJUSTE && amount.signum() < 0) {
            throw ApiException.badRequest("O valor deve ser positivo; o tipo (receita/despesa) define se entra ou sai.");
        }
        int installments = req.installments() == null ? 1 : req.installments();

        Wallet wallet = null;
        UUID target = null;
        Card card = null;
        CreditLine line = null;
        Invoice invoice = null;
        boolean consumes = req.consumesLimit() == null || req.consumesLimit();

        if (kind == TransactionKind.PAGAMENTO_FATURA) {
            if (req.cardId() != null || installments > 1 || req.categoryId() != null) {
                throw ApiException.badRequest("Pagamento de fatura não tem cartão, parcelas nem categoria.");
            }
            if (req.walletId() == null || req.invoiceId() == null) {
                throw ApiException.badRequest("Informe a carteira de onde sai o pagamento e a fatura.");
            }
            wallet = activeWallet(userId, req.walletId(), isNew || !req.walletId().equals(current.getWalletId()));
            invoice = invoiceService.invoice(userId, req.invoiceId());
            consumes = true;
        } else if (req.cardId() != null) {
            if (kind != TransactionKind.DESPESA && kind != TransactionKind.ESTORNO) {
                throw ApiException.badRequest("Cartão só é usado em compras e estornos.");
            }
            card = cards.findByIdAndUserId(req.cardId(), userId)
                    .orElseThrow(() -> ApiException.notFound("Cartão não encontrado."));
            if (card.isArchived() && (isNew || !card.getId().equals(current.getCardId()))) {
                throw ApiException.badRequest("O cartão \"" + card.getName() + "\" está arquivado.");
            }
            boolean credit = req.credit() != null ? req.credit() : card.hasCredit();
            if (credit && !card.hasCredit()) {
                throw ApiException.badRequest("O cartão \"" + card.getName() + "\" não tem função crédito.");
            }
            if (!credit && !card.hasDebit()) {
                throw ApiException.badRequest("O cartão \"" + card.getName() + "\" não tem carteira para débito.");
            }
            if (credit) {
                line = creditLines.findByIdAndUserId(card.getCreditLineId(), userId).orElseThrow();
                if (line.isArchived() && (isNew || current.getInvoiceId() == null)) {
                    throw ApiException.badRequest("O cartão de crédito \"" + line.getName() + "\" está arquivado.");
                }
                invoice = creditInvoice(userId, req, line, current);
                if (installments > 1 && kind != TransactionKind.DESPESA) {
                    throw ApiException.badRequest("Só compras podem ser parceladas.");
                }
                if (installments > 1 && foreign != null) {
                    throw ApiException.badRequest("Compra em moeda estrangeira não pode ser parcelada.");
                }
            } else {
                wallet = activeWallet(userId, card.getWalletId(), isNew || !card.getWalletId().equals(current.getWalletId()));
                consumes = true;
            }
        } else {
            if (req.invoiceId() != null) {
                throw ApiException.badRequest("Para lançar numa fatura, informe o cartão.");
            }
            if (req.walletId() == null) {
                throw ApiException.badRequest("Escolha a carteira ou o cartão.");
            }
            wallet = activeWallet(userId, req.walletId(), isNew || !req.walletId().equals(current.getWalletId()));
            consumes = true;
        }
        if (installments > 1 && invoice == null) {
            throw ApiException.badRequest("Só compras no crédito podem ser parceladas.");
        }
        if (foreign != null && kind != TransactionKind.DESPESA && kind != TransactionKind.ESTORNO) {
            throw ApiException.badRequest("Moeda estrangeira só em compras e estornos.");
        }

        if (kind == TransactionKind.TRANSFERENCIA) {
            if (req.targetWalletId() == null) {
                throw ApiException.badRequest("Informe a carteira de destino da transferência.");
            }
            if (req.targetWalletId().equals(wallet.getId())) {
                throw ApiException.badRequest("Origem e destino da transferência precisam ser carteiras diferentes.");
            }
            target = activeWallet(userId, req.targetWalletId(),
                    isNew || !req.targetWalletId().equals(current.getTargetWalletId())).getId();
        }

        UUID categoryId = category(userId, kind, req.categoryId());
        return new Plan(amount, wallet, target, categoryId, card, line, invoice, installments, consumes, foreign);
    }

    /** Fatura da compra no crédito: a informada, a atual (na edição sem mudar data/cartão) ou a da data. */
    private Invoice creditInvoice(UUID userId, TransactionRequest req, CreditLine line, Transaction current) {
        if (req.invoiceId() != null) {
            Invoice inv = invoiceService.invoice(userId, req.invoiceId());
            if (!inv.getCreditLineId().equals(line.getId())) {
                throw ApiException.badRequest("A fatura escolhida é de outro cartão de crédito.");
            }
            return inv;
        }
        if (current != null && current.getInvoiceId() != null && req.date().equals(current.getDate())) {
            Invoice inv = invoiceService.invoice(userId, current.getInvoiceId());
            if (inv.getCreditLineId().equals(line.getId())) return inv;
        }
        return invoiceService.invoiceFor(line, req.date());
    }

    private static Foreign foreign(TransactionRequest req) {
        String currency = Money.trimToNull(req.originalCurrency());
        if (currency == null) {
            if (req.originalAmount() != null || req.exchangeRate() != null || req.iof() != null) {
                throw ApiException.badRequest("Informe a moeda da compra internacional.");
            }
            return null;
        }
        currency = currency.toUpperCase();
        if ("BRL".equals(currency)) {
            throw ApiException.badRequest("Para compras em reais, não informe moeda estrangeira.");
        }
        if (req.originalAmount() == null || req.originalAmount().signum() <= 0) {
            throw ApiException.badRequest("Informe o valor na moeda original.");
        }
        if (req.exchangeRate() == null || req.exchangeRate().signum() <= 0) {
            throw ApiException.badRequest("Informe a cotação usada na conversão.");
        }
        if (req.iof() != null && req.iof().signum() < 0) {
            throw ApiException.badRequest("O IOF não pode ser negativo.");
        }
        return new Foreign(currency, Money.of(req.originalAmount()), req.exchangeRate().setScale(6, RoundingMode.HALF_EVEN),
                req.iof() == null ? null : Money.of(req.iof()));
    }

    private UUID category(UUID userId, TransactionKind kind, UUID categoryId) {
        if (categoryId == null) return null;
        if (kind != TransactionKind.RECEITA && kind != TransactionKind.DESPESA && kind != TransactionKind.ESTORNO) {
            throw ApiException.badRequest("Só receitas, despesas e estornos têm categoria.");
        }
        Category c = categories.findByIdAndUserId(categoryId, userId)
                .orElseThrow(() -> ApiException.notFound("Categoria não encontrada."));
        CategoryKind expected = kind == TransactionKind.RECEITA ? CategoryKind.RECEITA : CategoryKind.DESPESA;
        if (c.getKind() != expected) {
            throw ApiException.badRequest("A categoria \"" + c.getName() + "\" não é de "
                    + (expected == CategoryKind.RECEITA ? "receita." : "despesa."));
        }
        return c.getId();
    }

    private static void fill(Transaction t, TransactionRequest req, Plan p, BigDecimal amount, LocalDate date,
                             Invoice invoice) {
        t.setKind(req.kind());
        t.setStatus(req.status() == null ? TransactionStatus.CONFIRMADO : req.status());
        t.setDate(date);
        t.setDescription(req.description().trim());
        t.setAmount(amount);
        t.setWalletId(p.wallet() == null ? null : p.wallet().getId());
        t.setTargetWalletId(p.targetWalletId());
        t.setCategoryId(p.categoryId());
        t.setNotes(Money.trimToNull(req.notes()));
        t.setCardId(p.card() == null ? null : p.card().getId());
        t.setInvoiceId(invoice == null ? null : invoice.getId());
        t.setConsumesLimit(p.consumesLimit());
        Foreign f = p.foreign();
        if (f == null) t.setForeign(null, null, null, null);
        else t.setForeign(f.currency(), f.originalAmount(), f.rate(), f.iof());
    }

    /** Carteira do usuário; carteira arquivada só é aceita se o lançamento já estava nela. */
    private Wallet activeWallet(UUID userId, UUID walletId, boolean mustBeActive) {
        Wallet w = wallets.findByIdAndUserId(walletId, userId)
                .orElseThrow(() -> ApiException.notFound("Carteira não encontrada."));
        if (mustBeActive && w.isArchived()) {
            throw ApiException.badRequest("A carteira \"" + w.getName() + "\" está arquivada.");
        }
        return w;
    }

    // ---------------------------------------------------------------- nomes para a resposta

    record Names(Map<UUID, Wallet> wallets, Map<UUID, Account> accounts, Map<UUID, Category> categories,
                 Map<UUID, Card> cards, Map<UUID, CreditLine> lines, Map<UUID, Invoice> invoices) {
    }

    Names names(UUID userId) {
        return new Names(
                wallets.findByUserIdOrderByPositionAscNameAsc(userId).stream()
                        .collect(Collectors.toMap(Wallet::getId, Function.identity())),
                accounts.findByUserIdOrderByNameAsc(userId).stream()
                        .collect(Collectors.toMap(Account::getId, Function.identity())),
                categories.findByUserIdOrderByNameAsc(userId).stream()
                        .collect(Collectors.toMap(Category::getId, Function.identity())),
                cards.findByUserIdOrderByNameAsc(userId).stream()
                        .collect(Collectors.toMap(Card::getId, Function.identity())),
                creditLines.findByUserIdOrderByNameAsc(userId).stream()
                        .collect(Collectors.toMap(CreditLine::getId, Function.identity())),
                invoices.findByUserIdOrderByReferenceMonthAsc(userId).stream()
                        .collect(Collectors.toMap(Invoice::getId, Function.identity())));
    }

    static TransactionResponse toResponse(Transaction t, Names n) {
        Wallet w = t.getWalletId() == null ? null : n.wallets().get(t.getWalletId());
        Account a = w == null ? null : n.accounts().get(w.getAccountId());
        Wallet tw = t.getTargetWalletId() == null ? null : n.wallets().get(t.getTargetWalletId());
        Account ta = tw == null ? null : n.accounts().get(tw.getAccountId());
        Category c = t.getCategoryId() == null ? null : n.categories().get(t.getCategoryId());
        String categoryName = null;
        if (c != null) {
            Category parent = c.getParentId() == null ? null : n.categories().get(c.getParentId());
            categoryName = parent == null ? c.getName() : parent.getName() + " › " + c.getName();
        }
        Card card = t.getCardId() == null ? null : n.cards().get(t.getCardId());
        Invoice inv = t.getInvoiceId() == null ? null : n.invoices().get(t.getInvoiceId());
        CreditLine line = inv == null ? null : n.lines().get(inv.getCreditLineId());
        return new TransactionResponse(t.getId(), t.getKind(), t.getStatus(), t.getDate(), t.getDescription(),
                t.getAmount(), t.getWalletId(), w == null ? null : w.getName(), a == null ? null : a.getId(),
                a == null ? null : a.getName(), t.getTargetWalletId(), tw == null ? null : tw.getName(),
                ta == null ? null : ta.getName(), t.getCategoryId(), categoryName, t.getNotes(), t.getOrigin(),
                t.getCreatedAt(), t.getUpdatedAt(),
                t.getCardId(), card == null ? null : card.getName(),
                line == null ? null : line.getId(), line == null ? null : line.getName(),
                t.getInvoiceId(), inv == null ? null : inv.getReference().toString(), t.isConsumesLimit(),
                t.getInstallmentGroup(), t.getInstallmentNumber(), t.getInstallmentCount(),
                t.getOriginalCurrency(), t.getOriginalAmount(), t.getExchangeRate(), t.getIof(), t.getReversalOf(),
                t.getRecurrenceId(), t.getRecurrenceDate());
    }
}
