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
import com.controlefin.finance.FinanceDtos.RecurrenceLinkRequest;
import com.controlefin.finance.FinanceDtos.RecurrenceMonth;
import com.controlefin.finance.FinanceDtos.RecurrenceRequest;
import com.controlefin.finance.FinanceDtos.RecurrenceResponse;
import com.controlefin.finance.FinanceDtos.TransactionResponse;

import jakarta.validation.Valid;

/**
 * Recorrências do usuário logado: cadastro, mês previsto × realizado, pular ocorrência, candidatos a duplicado e
 * vínculo de um lançamento existente. Para lançar uma ocorrência, crie o lançamento em /api/transactions com
 * recurrenceId + recurrenceDate.
 */
@RestController
@RequestMapping("/api")
public class RecurrenceController {

    private final RecurrenceService recurrences;

    public RecurrenceController(RecurrenceService recurrences) {
        this.recurrences = recurrences;
    }

    @GetMapping("/recurrences")
    public List<RecurrenceResponse> list() {
        return recurrences.list(CurrentUser.id());
    }

    @GetMapping("/recurrences/month")
    public RecurrenceMonth month(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return recurrences.month(CurrentUser.id(), month == null ? YearMonth.from(Money.today()) : month);
    }

    @PostMapping("/recurrences")
    @ResponseStatus(HttpStatus.CREATED)
    public RecurrenceResponse create(@Valid @RequestBody RecurrenceRequest req) {
        return recurrences.create(CurrentUser.id(), req);
    }

    @PutMapping("/recurrences/{id}")
    public RecurrenceResponse update(@PathVariable UUID id, @Valid @RequestBody RecurrenceRequest req) {
        return recurrences.update(CurrentUser.id(), id, req);
    }

    @DeleteMapping("/recurrences/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        recurrences.delete(CurrentUser.id(), id);
    }

    @PostMapping("/recurrences/{id}/skips/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void skip(@PathVariable UUID id, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        recurrences.skip(CurrentUser.id(), id, date);
    }

    @DeleteMapping("/recurrences/{id}/skips/{date}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unskip(@PathVariable UUID id, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        recurrences.unskip(CurrentUser.id(), id, date);
    }

    @GetMapping("/recurrences/{id}/occurrences/{date}/candidates")
    public List<TransactionResponse> candidates(@PathVariable UUID id,
                                                @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return recurrences.candidates(CurrentUser.id(), id, date);
    }

    /** Liga um lançamento existente a uma ocorrência (corpo com campos nulos desliga). */
    @PutMapping("/transactions/{id}/recurrence")
    public TransactionResponse link(@PathVariable UUID id, @RequestBody(required = false) RecurrenceLinkRequest req) {
        return recurrences.link(CurrentUser.id(), id, req);
    }
}
