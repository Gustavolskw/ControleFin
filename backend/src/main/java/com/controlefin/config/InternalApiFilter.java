package com.controlefin.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Rotas /api/internal/** são consumidas apenas pela api-externa, dentro da rede Docker.
 * Camadas: (1) o gateway nginx nunca encaminha /api/internal de fora; (2) aqui exige o segredo de serviço
 * (INTERNAL_API_SECRET), comparado em tempo constante. Sem segredo configurado, as rotas internas não existem (404).
 * O usuário em si é autenticado pelo token de API pessoal (validado pelo próprio backend).
 */
@Component
public class InternalApiFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Secret";

    private final byte[] secret;

    public InternalApiFilter(@Value("${app.internal-api.secret:}") String secret) {
        if (!secret.isBlank() && secret.length() < 32) {
            throw new IllegalStateException("INTERNAL_API_SECRET precisa ter pelo menos 32 caracteres.");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!authorized(request.getHeader(HEADER))) {
            // 404 em vez de 401/403: não revela que a rota existe.
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        chain.doFilter(request, response);
    }

    boolean authorized(String provided) {
        if (secret.length == 0 || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(secret, provided.getBytes(StandardCharsets.UTF_8));
    }
}
