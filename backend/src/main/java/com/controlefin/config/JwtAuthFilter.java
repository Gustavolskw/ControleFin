package com.controlefin.config;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.controlefin.common.CurrentUser.AuthPrincipal;
import com.controlefin.user.User;
import com.controlefin.user.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Autenticação do site (JWT). O token só vale se o usuário existir, estiver ativo e a versão do token for a atual
 * (trocar a senha invalida as sessões antigas). Enquanto o primeiro acesso estiver pendente, só o perfil é liberado.
 * Não atua em /api/internal/** (rotas da api-externa, com autenticação própria).
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Rotas liberadas enquanto o primeiro acesso (trocar senha + perguntas) está pendente. */
    private static final Set<String> SETUP_PATHS = Set.of("/api/account", "/api/account/password",
            "/api/account/security-questions");

    private final JwtService jwtService;
    private final UserRepository users;

    public JwtAuthFilter(JwtService jwtService, UserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            User user = jwtService.validate(header.substring(7))
                    .flatMap(claims -> users.findById(claims.userId())
                            .filter(u -> u.isActive() && u.getTokenVersion() == claims.tokenVersion()))
                    .orElse(null);
            if (user != null) {
                String path = request.getRequestURI();
                if (user.isSetupRequired() && !SETUP_PATHS.contains(path) && !path.startsWith("/api/auth/")) {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"message\":\"Conclua o primeiro acesso: troque a senha inicial e "
                            + "cadastre as perguntas de segurança em Meu perfil.\"}");
                    return;
                }
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(user.getId(), user.getUsername()), null, authorities));
            }
        }
        chain.doFilter(request, response);
    }
}
