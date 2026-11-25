package com.controlefin.config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.controlefin.apitoken.ApiTokenService;
import com.controlefin.common.CurrentUser.AuthPrincipal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Autentica as rotas /api/internal/** (chamadas pela api-externa) com o token pessoal do usuário, recebido no header
 * X-Api-Token. Roda depois do {@link InternalApiFilter} (segredo de serviço). O backend é a única autoridade:
 * confere hash, revogação, validade e se o usuário está ativo. Permissões: ROLE_API (leitura) + SCOPE_WRITE
 * (se o token permite escrita).
 */
@Component
public class ApiTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Api-Token";

    private final ApiTokenService tokens;

    public ApiTokenFilter(ApiTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var resolved = tokens.resolve(request.getHeader(HEADER));
        if (resolved.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"message\":\"Token de API inválido, revogado ou vencido.\"}");
            return;
        }
        var r = resolved.get();
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_API"));
        if (r.token().isCanWrite()) authorities.add(new SimpleGrantedAuthority("SCOPE_WRITE"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(r.user().getId(), r.user().getUsername(), r.token().getId()), null, authorities));
        tokens.touch(r.token().getId());
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
