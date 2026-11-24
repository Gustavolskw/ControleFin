package com.controlefin.finance;

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
import com.controlefin.finance.FinanceDtos.TransactionFilter;
import com.controlefin.finance.FinanceDtos.TransactionRequest;
import com.controlefin.finance.FinanceDtos.TransactionResponse;
import com.controlefin.finance.FinanceEnums.CategoryKind;
import com.controlefin.finance.FinanceEnums.Origin;
import com.controlefin.finance.FinanceEnums.TransactionKind;
import com.controlefin.finance.FinanceEnums.TransactionStatus;

/** Lançamentos em carteiras. Regras de tipo, carteira e categoria validadas aqui e reforçadas por CHECKs no banco. */
@Service
public class TransactionService {

    static final int MAX_RESULTS = 1000;

    private final TransactionRepository transactions;
    private final WalletRepository wallets;
    private final AccountRepository accounts;
    private final CategoryRepository categories;

    public TransactionService(TransactionRepository transactions, WalletRepository wallets,
                              AccountRepository accounts, CategoryRepository categories) {
        this.transactions = transactions;
        this.wallets = wallets;
        this.accounts = accounts;
        this.categories = categories;
    }

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

    @Transactional
    public TransactionResponse create(UUID userId, TransactionRequest req, Origin origin) {
        Transaction t = new Transaction(userId, origin);
        apply(userId, t, req, true);
        transactions.saveAndFlush(t);
        return toResponse(t, names(userId));
    }

    @Transactional
    public TransactionResponse update(UUID userId, UUID id, TransactionRequest req) {
        Transaction t = transaction(userId, id);
        apply(userId, t, req, false);
        t.touch();
        transactions.flush();
        return toResponse(t, names(userId));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        transactions.delete(transaction(userId, id));
    }

    Transaction transaction(UUID userId, UUID id) {
        return transactions.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Lançamento não encontrado."));
    }

    private void apply(UUID userId, Transaction t, TransactionRequest req, boolean isNew) {
        TransactionKind kind = req.kind();
        var amount = Money.of(req.amount());
        if (amount.signum() == 0) {
            throw ApiException.badRequest("Informe um valor diferente de zero.");
        }
        if (kind != TransactionKind.AJUSTE && amount.signum() < 0) {
            throw ApiException.badRequest("O valor deve ser positivo; o tipo (receita/despesa) define se entra ou sai.");
        }

        Wallet wallet = activeWallet(userId, req.walletId(), isNew || !req.walletId().equals(t.getWalletId()));
        UUID target = null;
        if (kind == TransactionKind.TRANSFERENCIA) {
            if (req.targetWalletId() == null) {
                throw ApiException.badRequest("Informe a carteira de destino da transferência.");
            }
            if (req.targetWalletId().equals(wallet.getId())) {
                throw ApiException.badRequest("Origem e destino da transferência precisam ser carteiras diferentes.");
            }
            target = activeWallet(userId, req.targetWalletId(),
                    isNew || !req.targetWalletId().equals(t.getTargetWalletId())).getId();
        }

        UUID categoryId = null;
        if (req.categoryId() != null) {
            if (kind != TransactionKind.RECEITA && kind != TransactionKind.DESPESA) {
                throw ApiException.badRequest("Só receitas e despesas têm categoria.");
            }
            Category c = categories.findByIdAndUserId(req.categoryId(), userId)
                    .orElseThrow(() -> ApiException.notFound("Categoria não encontrada."));
            CategoryKind expected = kind == TransactionKind.RECEITA ? CategoryKind.RECEITA : CategoryKind.DESPESA;
            if (c.getKind() != expected) {
                throw ApiException.badRequest("A categoria \"" + c.getName() + "\" não é de "
                        + (expected == CategoryKind.RECEITA ? "receita." : "despesa."));
            }
            categoryId = c.getId();
        }

        t.setKind(kind);
        t.setStatus(req.status() == null ? TransactionStatus.CONFIRMADO : req.status());
        t.setDate(req.date());
        t.setDescription(req.description().trim());
        t.setAmount(amount);
        t.setWalletId(wallet.getId());
        t.setTargetWalletId(target);
        t.setCategoryId(categoryId);
        t.setNotes(Money.trimToNull(req.notes()));
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

    record Names(Map<UUID, Wallet> wallets, Map<UUID, Account> accounts, Map<UUID, Category> categories) {
    }

    Names names(UUID userId) {
        return new Names(
                wallets.findByUserIdOrderByPositionAscNameAsc(userId).stream()
                        .collect(Collectors.toMap(Wallet::getId, Function.identity())),
                accounts.findByUserIdOrderByNameAsc(userId).stream()
                        .collect(Collectors.toMap(Account::getId, Function.identity())),
                categories.findByUserIdOrderByNameAsc(userId).stream()
                        .collect(Collectors.toMap(Category::getId, Function.identity())));
    }

    static TransactionResponse toResponse(Transaction t, Names n) {
        Wallet w = n.wallets().get(t.getWalletId());
        Account a = w == null ? null : n.accounts().get(w.getAccountId());
        Wallet tw = t.getTargetWalletId() == null ? null : n.wallets().get(t.getTargetWalletId());
        Account ta = tw == null ? null : n.accounts().get(tw.getAccountId());
        Category c = t.getCategoryId() == null ? null : n.categories().get(t.getCategoryId());
        String categoryName = null;
        if (c != null) {
            Category parent = c.getParentId() == null ? null : n.categories().get(c.getParentId());
            categoryName = parent == null ? c.getName() : parent.getName() + " › " + c.getName();
        }
        return new TransactionResponse(t.getId(), t.getKind(), t.getStatus(), t.getDate(), t.getDescription(),
                t.getAmount(), t.getWalletId(), w == null ? null : w.getName(), a == null ? null : a.getId(),
                a == null ? null : a.getName(), t.getTargetWalletId(), tw == null ? null : tw.getName(),
                ta == null ? null : ta.getName(), t.getCategoryId(), categoryName, t.getNotes(), t.getOrigin(),
                t.getCreatedAt(), t.getUpdatedAt());
    }
}
