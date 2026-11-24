package com.controlefin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import jakarta.persistence.EntityManagerFactory;

@Configuration
public class PersistenceConfig {

    /** Substitui o gerenciador de transações padrão pelo que aplica o usuário logado no Row-Level Security. */
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new UserScopedTransactionManager(emf);
    }
}
