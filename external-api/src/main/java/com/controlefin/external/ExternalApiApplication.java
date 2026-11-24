package com.controlefin.external;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * API externa do ControleFin, consumida pelo servidor MCP (Claude).
 *
 * Regras:
 * - não tem banco nem login/cadastro/usuário: só inclusão, consulta e edição de dados financeiros;
 * - não decide quem é o usuário: repassa o token pessoal de API ao backend, que valida (existe, ativo, não
 *   expirado/revogado) e resolve o usuário;
 * - fala com o backend apenas pela rede interna (/api/internal/**), com o segredo de serviço.
 */
@SpringBootApplication
public class ExternalApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExternalApiApplication.class, args);
    }
}
