package com.controlefin.auth;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.controlefin.auth.AuthDtos.AccountResponse;
import com.controlefin.auth.AuthDtos.ChangePasswordRequest;
import com.controlefin.auth.AuthDtos.ChangeQuestionsRequest;
import com.controlefin.auth.AuthDtos.LoginRequest;
import com.controlefin.auth.AuthDtos.QuestionAnswer;
import com.controlefin.auth.AuthDtos.RegisterRequest;
import com.controlefin.auth.AuthDtos.ResetPasswordRequest;
import com.controlefin.auth.AuthDtos.TokenResponse;
import com.controlefin.common.ApiException;
import com.controlefin.config.JwtService;
import com.controlefin.user.Role;
import com.controlefin.user.SecurityQuestion;
import com.controlefin.user.User;
import com.controlefin.user.UserRepository;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Usuário ou senha inválidos.";
    private static final String RECOVERY_NOT_AVAILABLE = "Usuário não encontrado.";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final InviteService invites;
    private final int maxFailedAttempts;
    private final Duration lockDuration;
    /** Hash usado quando o usuário não existe, para o tempo de resposta não revelar quais usuários existem. */
    private final String dummyHash;

    private com.controlefin.apitoken.ApiTokenRepository apiTokens;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setApiTokens(com.controlefin.apitoken.ApiTokenRepository apiTokens) {
        this.apiTokens = apiTokens;
    }

    public AuthService(UserRepository users, PasswordEncoder encoder, JwtService jwt, InviteService invites,
                       @Value("${app.security.max-failed-attempts}") int maxFailedAttempts,
                       @Value("${app.security.lock-minutes}") long lockMinutes) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.invites = invites;
        this.maxFailedAttempts = maxFailedAttempts;
        this.lockDuration = Duration.ofMinutes(lockMinutes);
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public TokenResponse register(RegisterRequest req) {
        String username = normalizeUsername(req.username());
        if (users.existsByUsername(username)) {
            throw ApiException.conflict("Nome de usuário já está em uso.");
        }
        PasswordPolicy.validate(req.password(), username);
        List<SecurityQuestion> questions = hashQuestions(req.securityQuestions());
        // Só entra quem recebeu convite do administrador. Se algo falhar depois, a transação devolve o convite.
        invites.consume(req.inviteCode(), username);
        User user = new User();
        user.setUsername(username);
        user.setRole(Role.USER);
        user.setPasswordHash(encoder.encode(req.password()));
        user.setSecurityQuestions(questions);
        user.setLastLoginAt(Instant.now());
        users.save(user);
        return token(user);
    }

    /** Tentativas erradas são gravadas mesmo com a exceção (noRollbackFor), senão o bloqueio nunca aconteceria. */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse login(LoginRequest req) {
        Optional<User> found = users.findByUsername(normalizeUsername(req.username()));
        if (found.isEmpty()) {
            encoder.matches(req.password(), dummyHash);
            throw ApiException.unauthorized(INVALID_CREDENTIALS);
        }
        User user = found.get();
        ensureNotLocked(user);
        if (!encoder.matches(req.password(), user.getPasswordHash())) {
            user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
            lockIfNeeded(user, user.getFailedLoginAttempts());
            throw ApiException.unauthorized(INVALID_CREDENTIALS);
        }
        if (!user.isActive()) {
            throw ApiException.forbidden("Conta desativada. Fale com o administrador.");
        }
        user.setFailedLoginAttempts(0);
        user.setLastLoginAt(Instant.now());
        return token(user);
    }

    @Transactional(readOnly = true)
    public List<String> recoveryQuestions(String username) {
        User user = users.findByUsername(normalizeUsername(username))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, RECOVERY_NOT_AVAILABLE));
        ensureNotLocked(user);
        if (user.getSecurityQuestions().isEmpty()) {
            throw ApiException.badRequest("Este usuário ainda não cadastrou perguntas de segurança. "
                    + "Entre com a senha inicial definida no .env do servidor (DEFAULT_USER_PASSWORD).");
        }
        return user.getSecurityQuestions().stream().map(SecurityQuestion::question).toList();
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void resetPassword(ResetPasswordRequest req) {
        User user = users.findByUsername(normalizeUsername(req.username()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, RECOVERY_NOT_AVAILABLE));
        ensureNotLocked(user);
        PasswordPolicy.validate(req.newPassword(), user.getUsername());

        List<SecurityQuestion> questions = user.getSecurityQuestions();
        boolean allMatch = !questions.isEmpty() && questions.size() == req.answers().size();
        for (int i = 0; allMatch && i < questions.size(); i++) {
            allMatch = encoder.matches(normalizeAnswer(req.answers().get(i)), questions.get(i).answerHash());
        }
        if (!allMatch) {
            user.setFailedRecoveryAttempts(user.getFailedRecoveryAttempts() + 1);
            lockIfNeeded(user, user.getFailedRecoveryAttempts());
            throw ApiException.badRequest("Uma ou mais respostas estão incorretas.");
        }

        user.setPasswordHash(encoder.encode(req.newPassword()));
        user.setFailedRecoveryAttempts(0);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.invalidateTokens();
        // Recuperação de senha pode indicar conta comprometida: revoga também os tokens de API (Claude).
        if (apiTokens != null) apiTokens.revokeAll(user.getId());
    }

    @Transactional(readOnly = true)
    public AccountResponse account(UUID userId) {
        User user = getUser(userId);
        return new AccountResponse(user.getUsername(), user.getRole(),
                user.getSecurityQuestions().stream().map(SecurityQuestion::question).toList(),
                user.isPasswordChangeRequired(), user.isSetupRequired());
    }

    /** Troca a senha e invalida as outras sessões; devolve um token novo para a sessão atual continuar. */
    @Transactional
    public TokenResponse changePassword(UUID userId, ChangePasswordRequest req) {
        User user = getUser(userId);
        checkCurrentPassword(user, req.currentPassword());
        PasswordPolicy.validate(req.newPassword(), user.getUsername());
        if (encoder.matches(req.newPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("A nova senha deve ser diferente da atual.");
        }
        user.setPasswordHash(encoder.encode(req.newPassword()));
        user.setPasswordChangeRequired(false);
        user.invalidateTokens();
        users.saveAndFlush(user);
        return token(user);
    }

    @Transactional
    public void changeQuestions(UUID userId, ChangeQuestionsRequest req) {
        User user = getUser(userId);
        checkCurrentPassword(user, req.currentPassword());
        user.setSecurityQuestions(hashQuestions(req.securityQuestions()));
    }

    private TokenResponse token(User user) {
        return new TokenResponse(jwt.generate(user.getId(), user.getUsername(), user.getTokenVersion()),
                user.getUsername(), user.getRole(), user.isSetupRequired());
    }

    private User getUser(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.unauthorized("Não autenticado."));
    }

    private void checkCurrentPassword(User user, String password) {
        if (!encoder.matches(password, user.getPasswordHash())) {
            throw ApiException.badRequest("Senha atual incorreta.");
        }
    }

    private List<SecurityQuestion> hashQuestions(List<QuestionAnswer> items) {
        var distinct = new HashSet<String>();
        for (QuestionAnswer qa : items) {
            if (!distinct.add(qa.question().trim().toLowerCase(Locale.ROOT))) {
                throw ApiException.badRequest("As perguntas de segurança devem ser diferentes entre si.");
            }
        }
        return items.stream()
                .map(qa -> new SecurityQuestion(qa.question().trim(), encoder.encode(normalizeAnswer(qa.answer()))))
                .toList();
    }

    private void ensureNotLocked(User user) {
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Muitas tentativas inválidas. Tente novamente em alguns minutos.");
        }
    }

    private void lockIfNeeded(User user, int attempts) {
        if (attempts >= maxFailedAttempts) {
            user.setLockedUntil(Instant.now().plus(lockDuration));
            user.setFailedLoginAttempts(0);
            user.setFailedRecoveryAttempts(0);
        }
    }

    static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    /** Respostas comparadas sem diferenciar maiúsculas, acentos e espaços extras. */
    static String normalizeAnswer(String answer) {
        String noAccents = Normalizer.normalize(answer, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
