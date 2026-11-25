package com.controlefin.finance;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.controlefin.common.CurrentUser;
import com.controlefin.finance.FinanceDtos.CardRequest;
import com.controlefin.finance.FinanceDtos.CardResponse;
import com.controlefin.finance.FinanceDtos.CreditLineRequest;
import com.controlefin.finance.FinanceDtos.CreditLineResponse;
import com.controlefin.finance.FinanceDtos.InvoiceDetail;
import com.controlefin.finance.FinanceDtos.InvoiceSummary;

import jakarta.validation.Valid;

/** Cartões de crédito (limites), cartões e faturas do usuário logado. Compras e pagamentos vão por /api/transactions. */
@RestController
@RequestMapping("/api")
public class CardController {

    private final CardService cards;
    private final InvoiceService invoices;
    private final TransactionService transactions;

    public CardController(CardService cards, InvoiceService invoices, TransactionService transactions) {
        this.cards = cards;
        this.invoices = invoices;
        this.transactions = transactions;
    }

    // ---------------------------------------------------------------- limites (cartões de crédito)

    @GetMapping("/credit-lines")
    public List<CreditLineResponse> creditLines() {
        return cards.listCreditLines(CurrentUser.id());
    }

    @GetMapping("/credit-lines/{id}")
    public CreditLineResponse creditLine(@PathVariable UUID id) {
        return cards.getCreditLine(CurrentUser.id(), id);
    }

    @PostMapping("/credit-lines")
    @ResponseStatus(HttpStatus.CREATED)
    public CreditLineResponse createCreditLine(@Valid @RequestBody CreditLineRequest req) {
        return cards.createCreditLine(CurrentUser.id(), req);
    }

    @PutMapping("/credit-lines/{id}")
    public CreditLineResponse updateCreditLine(@PathVariable UUID id, @Valid @RequestBody CreditLineRequest req) {
        return cards.updateCreditLine(CurrentUser.id(), id, req);
    }

    @DeleteMapping("/credit-lines/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCreditLine(@PathVariable UUID id) {
        cards.deleteCreditLine(CurrentUser.id(), id);
    }

    @GetMapping("/credit-lines/{id}/invoices")
    public List<InvoiceSummary> invoices(@PathVariable UUID id) {
        return invoices.list(CurrentUser.id(), id);
    }

    @GetMapping("/invoices/{id}")
    public InvoiceDetail invoice(@PathVariable UUID id) {
        return transactions.invoice(CurrentUser.id(), id);
    }

    // ---------------------------------------------------------------- cartões

    @GetMapping("/cards")
    public List<CardResponse> cards() {
        return cards.listCards(CurrentUser.id());
    }

    @PostMapping("/cards")
    @ResponseStatus(HttpStatus.CREATED)
    public CardResponse createCard(@Valid @RequestBody CardRequest req) {
        return cards.createCard(CurrentUser.id(), req);
    }

    @PutMapping("/cards/{id}")
    public CardResponse updateCard(@PathVariable UUID id, @Valid @RequestBody CardRequest req) {
        return cards.updateCard(CurrentUser.id(), id, req);
    }

    @DeleteMapping("/cards/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCard(@PathVariable UUID id) {
        cards.deleteCard(CurrentUser.id(), id);
    }
}
