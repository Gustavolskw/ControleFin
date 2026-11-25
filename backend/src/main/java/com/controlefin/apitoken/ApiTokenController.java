package com.controlefin.apitoken;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.controlefin.apitoken.ApiTokenService.CreateRequest;
import com.controlefin.apitoken.ApiTokenService.TokenResponse;
import com.controlefin.audit.AuditService;
import com.controlefin.common.CurrentUser;

import jakarta.validation.Valid;

/** Site: tokens pessoais de API (para o Claude) e registro de atividade do próprio usuário. */
@RestController
@RequestMapping("/api/account")
public class ApiTokenController {

    private final ApiTokenService tokens;
    private final AuditService audit;

    public ApiTokenController(ApiTokenService tokens, AuditService audit) {
        this.tokens = tokens;
        this.audit = audit;
    }

    @GetMapping("/tokens")
    public List<TokenResponse> list() {
        return tokens.list(CurrentUser.id());
    }

    /** O token completo só aparece nesta resposta. */
    @PostMapping("/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse create(@Valid @RequestBody CreateRequest req) {
        return tokens.create(CurrentUser.id(), req);
    }

    @DeleteMapping("/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        tokens.revoke(CurrentUser.id(), id);
    }

    @GetMapping("/activity")
    public List<AuditService.Entry> activity() {
        return audit.recent(CurrentUser.id(), 100);
    }
}
