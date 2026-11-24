package com.controlefin.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.controlefin.user.Role;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record QuestionAnswer(
            @NotBlank @Size(max = 200) String question,
            @NotBlank @Size(min = 2, max = 200) String answer) {
    }

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 40)
            @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "use apenas letras, números, ponto, hífen ou sublinhado")
            String username,
            @NotBlank @Size(max = 100) String password,
            @NotNull @Size(min = 3, max = 3, message = "informe exatamente 3 perguntas de segurança")
            List<@Valid QuestionAnswer> securityQuestions,
            /** Código gerado pelo administrador. */
            @NotBlank(message = "informe o código de convite") @Size(max = 40) String inviteCode) {
    }

    public record LoginRequest(@NotBlank @Size(max = 40) String username, @NotBlank @Size(max = 100) String password) {
    }

    /** setupRequired = precisa trocar a senha inicial e/ou cadastrar as perguntas antes de usar o sistema. */
    public record TokenResponse(String token, String username, Role role, boolean setupRequired) {
    }

    public record RecoveryQuestionsResponse(List<String> questions) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Size(max = 40) String username,
            @NotNull @Size(min = 3, max = 3) List<@NotBlank @Size(max = 200) String> answers,
            @NotBlank @Size(max = 100) String newPassword) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 100) String currentPassword,
            @NotBlank @Size(max = 100) String newPassword) {
    }

    public record ChangeQuestionsRequest(
            @NotBlank @Size(max = 100) String currentPassword,
            @NotNull @Size(min = 3, max = 3, message = "informe exatamente 3 perguntas de segurança")
            List<@Valid QuestionAnswer> securityQuestions) {
    }

    public record AccountResponse(String username, Role role, List<String> securityQuestions,
                                  boolean passwordChangeRequired, boolean setupRequired) {
    }

    // ---------------------------------------------------------------- administração

    /** O código aparece só nesta resposta; no banco fica apenas o hash. */
    public record NewInviteResponse(UUID id, String code, Instant expiresAt) {
    }

    public record InviteResponse(UUID id, Instant createdAt, Instant expiresAt, String usedBy, Instant usedAt,
                                 Instant revokedAt, String status) {
    }

    public record UserResponse(UUID id, String username, Role role, boolean active, Instant createdAt,
                               Instant lastLoginAt, boolean you) {
    }

    public record ActiveRequest(@NotNull Boolean active) {
    }
}
