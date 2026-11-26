package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.controlefin.common.ApiException;
import com.controlefin.common.Money;
import com.controlefin.finance.FinanceDtos.AccountRequest;
import com.controlefin.finance.FinanceDtos.AccountResponse;
import com.controlefin.finance.FinanceDtos.AdjustRequest;
import com.controlefin.finance.FinanceDtos.WalletRequest;
import com.controlefin.finance.FinanceDtos.WalletResponse;
import com.controlefin.finance.FinanceDtos.WalletUpdateRequest;
import com.controlefin.finance.FinanceEnums.Origin;
import com.controlefin.finance.FinanceEnums.TransactionKind;
import com.controlefin.finance.FinanceEnums.TransactionStatus;

/** Contas e carteiras de um usuário. Todo método recebe o id do usuário logado e só enxerga os dados dele. */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final WalletRepository wallets;
    private final TransactionRepository transactions;
    private final CardRepository cards;
    private final RecurrenceRepository recurrences;
    private final InvestmentRepository investments;

    public AccountService(AccountRepository accounts, WalletRepository wallets, TransactionRepository transactions,
                          CardRepository cards, RecurrenceRepository recurrences, InvestmentRepository investments) {
        this.recurrences = recurrences;
        this.investments = investments;
        this.accounts = accounts;
        this.wallets = wallets;
        this.transactions = transactions;
        this.cards = cards;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(UUID userId, LocalDate asOf) {
        Map<UUID, BigDecimal> balances = balances(userId, asOf);
        Map<UUID, List<Wallet>> byAccount = wallets.findByUserIdOrderByPositionAscNameAsc(userId).stream()
                .collect(Collectors.groupingBy(Wallet::getAccountId));
        return accounts.findByUserIdOrderByNameAsc(userId).stream()
                .map(a -> toResponse(a, byAccount.getOrDefault(a.getId(), List.of()), balances))
                .sorted(Comparator.comparing(AccountResponse::archived))
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(UUID userId, UUID id) {
        Account account = account(userId, id);
        return toResponse(account, wallets.findByUserIdAndAccountIdOrderByPositionAscNameAsc(userId, id),
                balances(userId, Money.today()));
    }

    @Transactional
    public AccountResponse create(UUID userId, AccountRequest req) {
        String name = req.name().trim();
        if (accounts.existsByUserIdAndNameIgnoreCase(userId, name)) {
            throw ApiException.conflict("Já existe uma conta com o nome \"" + name + "\".");
        }
        Account account = new Account(userId);
        apply(account, req);
        accounts.save(account);

        List<WalletRequest> items = req.wallets() == null || req.wallets().isEmpty()
                ? List.of(new WalletRequest("Principal", BigDecimal.ZERO))
                : req.wallets();
        var names = new java.util.HashSet<String>();
        int position = 0;
        for (WalletRequest w : items) {
            if (!names.add(w.name().trim().toLowerCase())) {
                throw ApiException.badRequest("As carteiras da conta precisam ter nomes diferentes.");
            }
            Wallet wallet = new Wallet(userId, account.getId());
            wallet.setName(w.name().trim());
            wallet.setInitialBalance(Money.of(w.initialBalance()));
            wallet.setPosition(position++);
            wallets.save(wallet);
        }
        accounts.flush();
        return get(userId, account.getId());
    }

    @Transactional
    public AccountResponse update(UUID userId, UUID id, AccountRequest req) {
        Account account = account(userId, id);
        String name = req.name().trim();
        if (accounts.existsByUserIdAndNameIgnoreCaseAndIdNot(userId, name, id)) {
            throw ApiException.conflict("Já existe uma conta com o nome \"" + name + "\".");
        }
        apply(account, req);
        return get(userId, id);
    }

    /** Só remove conta sem lançamentos; com histórico, o caminho é arquivar. */
    @Transactional
    public void delete(UUID userId, UUID id) {
        Account account = account(userId, id);
        if (investments.existsByUserIdAndAccountId(userId, id)) {
            throw ApiException.badRequest("Esta conta é a instituição de um investimento. Mude o investimento antes.");
        }
        for (Wallet w : wallets.findByUserIdAndAccountIdOrderByPositionAscNameAsc(userId, id)) {
            if (transactions.walletInUse(userId, w.getId())) {
                throw ApiException.badRequest("Esta conta tem lançamentos. Arquive-a em vez de excluir.");
            }
            if (cards.existsByUserIdAndWalletId(userId, w.getId())) {
                throw ApiException.badRequest("Esta conta tem cartões ligados. Remova ou mude os cartões antes.");
            }
            if (recurrences.existsByUserIdAndWalletId(userId, w.getId())) {
                throw ApiException.badRequest("Esta conta é usada por uma recorrência. Mude a recorrência antes.");
            }
            if (investments.existsByUserIdAndWalletId(userId, w.getId())) {
                throw ApiException.badRequest("Esta conta é usada por um investimento. Mude o investimento antes.");
            }
        }
        accounts.delete(account);
    }

    // ---------------------------------------------------------------- carteiras

    @Transactional
    public AccountResponse addWallet(UUID userId, UUID accountId, WalletRequest req) {
        account(userId, accountId);
        String name = req.name().trim();
        if (wallets.existsByAccountIdAndNameIgnoreCase(accountId, name)) {
            throw ApiException.conflict("Esta conta já tem uma carteira chamada \"" + name + "\".");
        }
        Wallet wallet = new Wallet(userId, accountId);
        wallet.setName(name);
        wallet.setInitialBalance(Money.of(req.initialBalance()));
        wallet.setPosition(wallets.findByUserIdAndAccountIdOrderByPositionAscNameAsc(userId, accountId).size());
        wallets.save(wallet);
        return get(userId, accountId);
    }

    @Transactional
    public AccountResponse updateWallet(UUID userId, UUID walletId, WalletUpdateRequest req) {
        Wallet wallet = wallet(userId, walletId);
        String name = req.name().trim();
        if (wallets.existsByAccountIdAndNameIgnoreCaseAndIdNot(wallet.getAccountId(), name, walletId)) {
            throw ApiException.conflict("Esta conta já tem uma carteira chamada \"" + name + "\".");
        }
        wallet.setName(name);
        wallet.setInitialBalance(Money.of(req.initialBalance()));
        wallet.setArchived(Boolean.TRUE.equals(req.archived()));
        return get(userId, wallet.getAccountId());
    }

    @Transactional
    public void deleteWallet(UUID userId, UUID walletId) {
        Wallet wallet = wallet(userId, walletId);
        if (transactions.walletInUse(userId, walletId)) {
            throw ApiException.badRequest("Esta carteira tem lançamentos. Arquive-a em vez de excluir.");
        }
        if (cards.existsByUserIdAndWalletId(userId, walletId)) {
            throw ApiException.badRequest("Esta carteira tem cartões ligados. Remova ou mude os cartões antes.");
        }
        if (recurrences.existsByUserIdAndWalletId(userId, walletId)) {
            throw ApiException.badRequest("Esta carteira é usada por uma recorrência. Mude a recorrência antes.");
        }
        if (investments.existsByUserIdAndWalletId(userId, walletId)) {
            throw ApiException.badRequest("Esta carteira é usada por um investimento. Mude o investimento antes.");
        }
        if (wallets.findByUserIdAndAccountIdOrderByPositionAscNameAsc(userId, wallet.getAccountId()).size() <= 1) {
            throw ApiException.badRequest("A conta precisa ter pelo menos uma carteira.");
        }
        wallets.delete(wallet);
    }

    /** Ajusta o saldo para o valor real informado, lançando a diferença como AJUSTE. */
    @Transactional
    public AccountResponse adjust(UUID userId, UUID walletId, AdjustRequest req) {
        Wallet wallet = wallet(userId, walletId);
        LocalDate date = req.date() == null ? Money.today() : req.date();
        BigDecimal current = balances(userId, date).getOrDefault(walletId, BigDecimal.ZERO);
        BigDecimal diff = Money.of(req.balance()).subtract(current);
        if (diff.signum() == 0) {
            throw ApiException.badRequest("O saldo já é este valor. Nada a ajustar.");
        }
        Transaction t = new Transaction(userId, Origin.WEB);
        t.setKind(TransactionKind.AJUSTE);
        t.setStatus(TransactionStatus.CONFIRMADO);
        t.setDate(date);
        t.setAmount(diff);
        t.setWalletId(walletId);
        String desc = Money.trimToNull(req.description());
        t.setDescription(desc == null ? "Ajuste de saldo" : desc);
        transactions.save(t);
        transactions.flush();
        return get(userId, wallet.getAccountId());
    }

    // ---------------------------------------------------------------- apoio

    Map<UUID, BigDecimal> balances(UUID userId, LocalDate asOf) {
        return wallets.balances(userId, asOf).stream()
                .collect(Collectors.toMap(WalletRepository.WalletBalance::getWalletId, b -> Money.nz(b.getBalance())));
    }

    Account account(UUID userId, UUID id) {
        return accounts.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Conta não encontrada."));
    }

    Wallet wallet(UUID userId, UUID id) {
        return wallets.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Carteira não encontrada."));
    }

    static AccountResponse toResponse(Account a, List<Wallet> ws, Map<UUID, BigDecimal> balances) {
        List<WalletResponse> items = new ArrayList<>();
        BigDecimal total = Money.nz(null);
        for (Wallet w : ws) {
            BigDecimal balance = balances.getOrDefault(w.getId(), Money.nz(w.getInitialBalance()));
            items.add(new WalletResponse(w.getId(), a.getId(), w.getName(), w.getInitialBalance(), balance,
                    w.getPosition(), w.isArchived()));
            total = total.add(balance);
        }
        return new AccountResponse(a.getId(), a.getName(), a.getType(), a.getHolder(), a.getColor(), a.isArchived(),
                total, items);
    }

    private static void apply(Account account, AccountRequest req) {
        account.setName(req.name().trim());
        account.setType(req.type());
        account.setHolder(req.holder());
        account.setColor(Money.trimToNull(req.color()));
        account.setArchived(Boolean.TRUE.equals(req.archived()));
    }
}
