package com.controlefin.config;

import java.util.UUID;

import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.controlefin.common.CurrentUser.AuthPrincipal;

import jakarta.persistence.EntityManagerFactory;

/**
 * Segunda camada do isolamento por usuário: no início de cada transação informa ao PostgreSQL quem é o usuário
 * logado (app.user_id, válido só nesta transação). As políticas de Row-Level Security das tabelas financeiras só
 * mostram/aceitam linhas desse usuário. Sem usuário autenticado o valor fica vazio e nenhuma linha financeira é visível.
 */
public class UserScopedTransactionManager extends JpaTransactionManager {

    public UserScopedTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        if (holder == null) {
            return;
        }
        holder.getEntityManager()
                .createNativeQuery("select set_config('app.user_id', :uid, true)")
                .setParameter("uid", currentUserId())
                .getSingleResult();
    }

    static String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthPrincipal principal) {
            UUID id = principal.userId();
            return id.toString();
        }
        return "";
    }
}
