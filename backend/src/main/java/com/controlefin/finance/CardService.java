package com.controlefin.finance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
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
import com.controlefin.finance.FinanceDtos.CardInline;
import com.controlefin.finance.FinanceDtos.CardRequest;
import com.controlefin.finance.FinanceDtos.CardResponse;
import com.controlefin.finance.FinanceDtos.CreditLineRequest;
import com.controlefin.finance.FinanceDtos.CreditLineResponse;
import com.controlefin.finance.FinanceDtos.InvoiceSummary;
import com.controlefin.finance.FinanceEnums.CardFunction;
import com.controlefin.finance.FinanceEnums.InvoiceStatus;

/** Limites de crédito (compartilhados pelos cartões) e cartões. Sempre do usuário logado. */
@Service
public class CardService {

    private final CreditLineRepository creditLines;
    private final CardRepository cards;
    private final InvoiceRepository invoices;
    private final WalletRepository wallets;
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final InvoiceService invoiceService;

    public CardService(CreditLineRepository creditLines, CardRepository cards, InvoiceRepository invoices,
                       WalletRepository wallets, AccountRepository accounts, TransactionRepository transactions,
                       InvoiceService invoiceService) {
        this.creditLines = creditLines;
        this.cards = cards;
        this.invoices = invoices;
        this.wallets = wallets;
        this.accounts = accounts;
        this.transactions = transactions;
        this.invoiceService = invoiceService;
    }

    // ---------------------------------------------------------------- limites de crédito

    /** Limites com uso, fatura aberta e fatura a pagar. Cria a fatura aberta de hoje se ainda não existe. */
    @Transactional
    public List<CreditLineResponse> listCreditLines(UUID userId) {
        LocalDate today = Money.today();
        List<CreditLine> lines = creditLines.findByUserIdOrderByNameAsc(userId);
        for (CreditLine l : lines) {
            if (!l.isArchived()) invoiceService.invoiceFor(l, today);
        }
        Names n = names(userId);
        var totals = invoiceService.totals(userId);
        Map<UUID, List<Invoice>> byLine = invoices.findByUserIdOrderByReferenceMonthAsc(userId).stream()
                .collect(Collectors.groupingBy(Invoice::getCreditLineId));
        List<CardResponse> allCards = cards.findByUserIdOrderByNameAsc(userId).stream().map(c -> toResponse(c, n)).toList();
        return lines.stream().map(l -> {
            List<InvoiceSummary> sums = InvoiceService.summaries(l, byLine.getOrDefault(l.getId(), List.of()),
                    totals, today);
            BigDecimal used = InvoiceService.used(sums);
            InvoiceSummary current = sums.stream().filter(s -> s.status() == InvoiceStatus.ABERTA).findFirst().orElse(null);
            InvoiceSummary due = sums.stream()
                    .filter(s -> s.status() == InvoiceStatus.FECHADA || s.status() == InvoiceStatus.VENCIDA)
                    .findFirst().orElse(null);
            BigDecimal toPay = sums.stream()
                    .filter(s -> s.status() == InvoiceStatus.ABERTA || s.status() == InvoiceStatus.FECHADA
                            || s.status() == InvoiceStatus.VENCIDA)
                    .map(s -> s.outstanding().max(BigDecimal.ZERO)).reduce(Money.nz(null), BigDecimal::add);
            Wallet pw = l.getPaymentWalletId() == null ? null : n.wallets().get(l.getPaymentWalletId());
            return new CreditLineResponse(l.getId(), l.getName(), l.getColor(), l.getLimitAmount(), l.getClosingDay(),
                    l.getDueDay(), l.getPaymentWalletId(), pw == null ? null : walletLabel(pw, n), l.isArchived(),
                    used, l.getLimitAmount().subtract(used),
                    allCards.stream().filter(c -> l.getId().equals(c.creditLineId())).toList(), current, due, toPay);
        }).sorted(Comparator.comparing(CreditLineResponse::archived)).toList();
    }

    @Transactional
    public CreditLineResponse getCreditLine(UUID userId, UUID id) {
        creditLine(userId, id);
        return listCreditLines(userId).stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public CreditLineResponse createCreditLine(UUID userId, CreditLineRequest req) {
        String name = req.name().trim();
        if (creditLines.existsByUserIdAndNameIgnoreCase(userId, name)) {
            throw ApiException.conflict("Já existe um cartão de crédito chamado \"" + name + "\".");
        }
        CreditLine line = new CreditLine(userId);
        apply(userId, line, req);
        creditLines.saveAndFlush(line);

        Set<String> names = new HashSet<>();
        for (CardInline ci : req.cards() == null ? List.<CardInline>of() : req.cards()) {
            String cardName = ci.name().trim();
            if (!names.add(cardName.toLowerCase())) {
                throw ApiException.badRequest("Os cartões precisam ter nomes diferentes.");
            }
            if (cards.existsByUserIdAndNameIgnoreCase(userId, cardName)) {
                throw ApiException.conflict("Já existe um cartão chamado \"" + cardName + "\".");
            }
            Card card = new Card(userId);
            card.setName(cardName);
            card.setFormat(ci.format());
            card.setFunction(CardFunction.CREDITO_DEBITO);
            card.setCreditLineId(line.getId());
            card.setWalletId(ci.walletId() == null ? null : activeWallet(userId, ci.walletId()).getId());
            cards.save(card);
        }
        cards.flush();
        return getCreditLine(userId, line.getId());
    }

    @Transactional
    public CreditLineResponse updateCreditLine(UUID userId, UUID id, CreditLineRequest req) {
        CreditLine line = creditLine(userId, id);
        String name = req.name().trim();
        if (creditLines.existsByUserIdAndNameIgnoreCaseAndIdNot(userId, name, id)) {
            throw ApiException.conflict("Já existe um cartão de crédito chamado \"" + name + "\".");
        }
        boolean daysChanged = line.getClosingDay() != req.closingDay() || line.getDueDay() != req.dueDay();
        apply(userId, line, req);
        line.setArchived(Boolean.TRUE.equals(req.archived()));
        if (daysChanged) {
            invoiceService.recalculateOpenDates(line, Money.today());
        }
        creditLines.flush();
        return getCreditLine(userId, id);
    }

    /** Só remove limite sem compras/pagamentos; com histórico, arquive. */
    @Transactional
    public void deleteCreditLine(UUID userId, UUID id) {
        CreditLine line = creditLine(userId, id);
        if (transactions.creditLineInUse(userId, id)) {
            throw ApiException.badRequest("Este cartão tem compras ou pagamentos. Arquive-o em vez de excluir.");
        }
        for (Card c : cards.findByUserIdOrderByNameAsc(userId)) {
            if (id.equals(c.getCreditLineId()) && transactions.existsByUserIdAndCardId(userId, c.getId())) {
                throw ApiException.badRequest("Um cartão deste limite tem lançamentos. Arquive em vez de excluir.");
            }
        }
        creditLines.delete(line);
    }

    private void apply(UUID userId, CreditLine line, CreditLineRequest req) {
        if (req.closingDay().equals(req.dueDay())) {
            throw ApiException.badRequest("O dia do fechamento e o do vencimento precisam ser diferentes.");
        }
        line.setName(req.name().trim());
        line.setColor(Money.trimToNull(req.color()));
        line.setLimitAmount(Money.of(req.limit()));
        line.setClosingDay(req.closingDay());
        line.setDueDay(req.dueDay());
        line.setPaymentWalletId(req.paymentWalletId() == null ? null : wallet(userId, req.paymentWalletId()).getId());
    }

    CreditLine creditLine(UUID userId, UUID id) {
        return creditLines.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Cartão de crédito não encontrado."));
    }

    // ---------------------------------------------------------------- cartões

    @Transactional(readOnly = true)
    public List<CardResponse> listCards(UUID userId) {
        Names n = names(userId);
        return cards.findByUserIdOrderByNameAsc(userId).stream().map(c -> toResponse(c, n))
                .sorted(Comparator.comparing(CardResponse::archived)).toList();
    }

    @Transactional
    public CardResponse createCard(UUID userId, CardRequest req) {
        String name = req.name().trim();
        if (cards.existsByUserIdAndNameIgnoreCase(userId, name)) {
            throw ApiException.conflict("Já existe um cartão chamado \"" + name + "\".");
        }
        Card card = new Card(userId);
        apply(userId, card, req, true);
        cards.saveAndFlush(card);
        return toResponse(card, names(userId));
    }

    @Transactional
    public CardResponse updateCard(UUID userId, UUID id, CardRequest req) {
        Card card = card(userId, id);
        String name = req.name().trim();
        if (cards.existsByUserIdAndNameIgnoreCaseAndIdNot(userId, name, id)) {
            throw ApiException.conflict("Já existe um cartão chamado \"" + name + "\".");
        }
        boolean used = transactions.existsByUserIdAndCardId(userId, id);
        if (used && (req.function() != card.getFunction()
                || (req.creditLineId() != null && !req.creditLineId().equals(card.getCreditLineId())))) {
            throw ApiException.badRequest("Este cartão já tem lançamentos: não dá para mudar a função nem o limite. "
                    + "Arquive-o e cadastre outro.");
        }
        apply(userId, card, req, false);
        card.setArchived(Boolean.TRUE.equals(req.archived()));
        cards.flush();
        return toResponse(card, names(userId));
    }

    @Transactional
    public void deleteCard(UUID userId, UUID id) {
        Card card = card(userId, id);
        if (transactions.existsByUserIdAndCardId(userId, id)) {
            throw ApiException.badRequest("Este cartão tem lançamentos. Arquive-o em vez de excluir.");
        }
        cards.delete(card);
    }

    private void apply(UUID userId, Card card, CardRequest req, boolean isNew) {
        UUID lineId = null;
        UUID walletId = null;
        if (req.function() == CardFunction.CREDITO_DEBITO) {
            if (req.creditLineId() == null) {
                throw ApiException.badRequest("Escolha o limite (cartão de crédito) deste cartão.");
            }
            CreditLine line = creditLine(userId, req.creditLineId());
            if (line.isArchived() && (isNew || !line.getId().equals(card.getCreditLineId()))) {
                throw ApiException.badRequest("O cartão de crédito \"" + line.getName() + "\" está arquivado.");
            }
            lineId = line.getId();
            if (req.walletId() != null) walletId = wallet(userId, req.walletId()).getId();
        } else {
            if (req.walletId() == null) {
                throw ApiException.badRequest("Escolha a carteira de onde sai o dinheiro deste cartão.");
            }
            walletId = wallet(userId, req.walletId()).getId();
        }
        if (walletId != null && (isNew || !walletId.equals(card.getWalletId()))) {
            activeWallet(userId, walletId);
        }
        card.setName(req.name().trim());
        card.setFormat(req.format());
        card.setFunction(req.function());
        card.setCreditLineId(lineId);
        card.setWalletId(walletId);
    }

    Card card(UUID userId, UUID id) {
        return cards.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Cartão não encontrado."));
    }

    private Wallet wallet(UUID userId, UUID id) {
        return wallets.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Carteira não encontrada."));
    }

    private Wallet activeWallet(UUID userId, UUID id) {
        Wallet w = wallet(userId, id);
        if (w.isArchived()) throw ApiException.badRequest("A carteira \"" + w.getName() + "\" está arquivada.");
        return w;
    }

    // ---------------------------------------------------------------- respostas

    record Names(Map<UUID, Wallet> wallets, Map<UUID, Account> accounts, Map<UUID, CreditLine> lines) {
    }

    private Names names(UUID userId) {
        return new Names(
                wallets.findByUserIdOrderByPositionAscNameAsc(userId).stream()
                        .collect(Collectors.toMap(Wallet::getId, Function.identity())),
                accounts.findByUserIdOrderByNameAsc(userId).stream()
                        .collect(Collectors.toMap(Account::getId, Function.identity())),
                creditLines.findByUserIdOrderByNameAsc(userId).stream()
                        .collect(Collectors.toMap(CreditLine::getId, Function.identity())));
    }

    private static String walletLabel(Wallet w, Names n) {
        Account a = n.accounts().get(w.getAccountId());
        return a == null ? w.getName() : a.getName() + " · " + w.getName();
    }

    private static CardResponse toResponse(Card c, Names n) {
        Wallet w = c.getWalletId() == null ? null : n.wallets().get(c.getWalletId());
        Account a = w == null ? null : n.accounts().get(w.getAccountId());
        CreditLine l = c.getCreditLineId() == null ? null : n.lines().get(c.getCreditLineId());
        return new CardResponse(c.getId(), c.getName(), c.getFormat(), c.getFunction(), c.getCreditLineId(),
                l == null ? null : l.getName(), c.getWalletId(), w == null ? null : w.getName(),
                a == null ? null : a.getName(), c.isArchived());
    }

    /** Usado pelo painel: total a pagar de todos os limites. */
    static BigDecimal toPay(List<CreditLineResponse> lines) {
        return lines.stream().map(CreditLineResponse::toPay).reduce(Money.nz(null), BigDecimal::add);
    }
}
