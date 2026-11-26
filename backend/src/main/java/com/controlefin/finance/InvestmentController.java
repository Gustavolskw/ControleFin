package com.controlefin.finance;

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
import com.controlefin.finance.FinanceEnums.Origin;
import com.controlefin.finance.InvestmentDtos.InvestmentDetail;
import com.controlefin.finance.InvestmentDtos.InvestmentRequest;
import com.controlefin.finance.InvestmentDtos.InvestmentResponse;
import com.controlefin.finance.InvestmentDtos.MovementRequest;
import com.controlefin.finance.InvestmentDtos.Portfolio;

import jakarta.validation.Valid;

/** Investimentos pelo site (caixinhas, CDB, Tesouro, ações, FIIs, cripto…). */
@RestController
@RequestMapping("/api/investments")
public class InvestmentController {

    private final InvestmentService investments;

    public InvestmentController(InvestmentService investments) {
        this.investments = investments;
    }

    @GetMapping
    public Portfolio portfolio() {
        return investments.portfolio(CurrentUser.id());
    }

    @GetMapping("/{id}")
    public InvestmentDetail detail(@PathVariable UUID id) {
        return investments.detail(CurrentUser.id(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvestmentResponse create(@Valid @RequestBody InvestmentRequest req) {
        return investments.create(CurrentUser.id(), req);
    }

    @PutMapping("/{id}")
    public InvestmentResponse update(@PathVariable UUID id, @Valid @RequestBody InvestmentRequest req) {
        return investments.update(CurrentUser.id(), id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        investments.delete(CurrentUser.id(), id);
    }

    @PostMapping("/{id}/movements")
    @ResponseStatus(HttpStatus.CREATED)
    public InvestmentDetail addMovement(@PathVariable UUID id, @Valid @RequestBody MovementRequest req) {
        return investments.addMovement(CurrentUser.id(), id, req, Origin.WEB);
    }

    @PutMapping("/{id}/movements/{movementId}")
    public InvestmentDetail updateMovement(@PathVariable UUID id, @PathVariable UUID movementId,
                                           @Valid @RequestBody MovementRequest req) {
        return investments.updateMovement(CurrentUser.id(), id, movementId, req);
    }

    @DeleteMapping("/{id}/movements/{movementId}")
    public InvestmentDetail deleteMovement(@PathVariable UUID id, @PathVariable UUID movementId) {
        return investments.deleteMovement(CurrentUser.id(), id, movementId);
    }
}
