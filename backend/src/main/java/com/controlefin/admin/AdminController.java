package com.controlefin.admin;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.controlefin.auth.AuthDtos.ActiveRequest;
import com.controlefin.auth.AuthDtos.InviteResponse;
import com.controlefin.auth.AuthDtos.NewInviteResponse;
import com.controlefin.auth.AuthDtos.UserResponse;
import com.controlefin.auth.InviteService;
import com.controlefin.common.ApiException;
import com.controlefin.common.CurrentUser;
import com.controlefin.user.User;
import com.controlefin.user.UserRepository;

import jakarta.validation.Valid;

/**
 * Administração (somente ROLE_ADMIN, garantido no SecurityConfig): convites e ativação de contas.
 * Nenhum endpoint daqui expõe dados financeiros de usuários — só nome, papel, status e datas de acesso.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserRepository users;
    private final InviteService invites;

    public AdminController(UserRepository users, InviteService invites) {
        this.users = users;
        this.invites = invites;
    }

    @GetMapping("/users")
    @Transactional(readOnly = true)
    public List<UserResponse> users() {
        UUID me = CurrentUser.id();
        return users.findAll().stream()
                .sorted(Comparator.comparing(User::getCreatedAt))
                .map(u -> new UserResponse(u.getId(), u.getUsername(), u.getRole(), u.isActive(), u.getCreatedAt(),
                        u.getLastLoginAt(), u.getId().equals(me)))
                .toList();
    }

    /** Desativar bloqueia o login e derruba as sessões abertas (o filtro JWT recusa usuário inativo). */
    @PutMapping("/users/{id}/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void setActive(@PathVariable UUID id, @Valid @RequestBody ActiveRequest req) {
        if (id.equals(CurrentUser.id())) {
            throw ApiException.badRequest("Você não pode desativar o próprio usuário.");
        }
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("Usuário não encontrado."));
        user.setActive(req.active());
    }

    @GetMapping("/invites")
    public List<InviteResponse> invites() {
        return invites.list();
    }

    @PostMapping("/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public NewInviteResponse createInvite() {
        return invites.create(CurrentUser.id());
    }

    @DeleteMapping("/invites/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvite(@PathVariable UUID id) {
        invites.revoke(id);
    }
}
