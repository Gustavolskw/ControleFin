package com.controlefin.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.controlefin.auth.AuthDtos.QuestionAnswer;
import com.controlefin.auth.AuthDtos.RegisterRequest;
import com.controlefin.common.ApiException;
import com.controlefin.config.JwtService;
import com.controlefin.user.User;
import com.controlefin.user.UserRepository;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/** Segurança: o cadastro nunca pode criar usuário sem um código de convite válido gerado pelo administrador. */
class RegisterRequiresInviteTest {

    private static final List<QuestionAnswer> QUESTIONS = List.of(
            new QuestionAnswer("Pergunta A?", "aa"),
            new QuestionAnswer("Pergunta B?", "bb"),
            new QuestionAnswer("Pergunta C?", "cc"));

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static RegisterRequest request(String inviteCode) {
        return new RegisterRequest("novo_usuario", "Senha@Forte1", QUESTIONS, inviteCode);
    }

    static Stream<Arguments> missingCodes() {
        return Stream.of(Arguments.of((String) null), Arguments.of(""), Arguments.of("   "));
    }

    /** A validação da requisição (@Valid no controller) recusa cadastro sem código. */
    @ParameterizedTest
    @MethodSource("missingCodes")
    void requisicaoSemCodigoEhInvalida(String code) {
        assertFalse(validator.validate(request(code)).isEmpty());
    }

    /** Mesmo que a validação fosse contornada, o serviço exige um convite válido antes de gravar. */
    @Test
    void codigoInvalidoNaoGravaUsuario() {
        UserRepository users = mock(UserRepository.class);
        InviteService invites = mock(InviteService.class);
        when(users.existsByUsername(anyString())).thenReturn(false);
        doThrow(ApiException.badRequest("Código de convite inválido, expirado ou já utilizado."))
                .when(invites).consume(any(), anyString());
        AuthService service = new AuthService(users, new BCryptPasswordEncoder(4),
                new JwtService("segredo-de-teste-com-mais-de-32-caracteres", 1), invites, 5, 15);

        assertThrows(ApiException.class, () -> service.register(request("ABCDE-FGHJK")));
        assertThrows(ApiException.class, () -> service.register(request(null)));
        verify(users, never()).save(any(User.class));
    }

    /** O serviço de convites nunca considera um código vazio como válido nem consulta o banco nesse caso. */
    @Test
    void servicoDeConviteRecusaCodigoAusente() {
        InviteRepository repo = mock(InviteRepository.class);
        InviteService invites = new InviteService(repo);
        assertThrows(ApiException.class, () -> invites.consume(null, "x"));
        assertThrows(ApiException.class, () -> invites.consume("  ", "x"));
        verify(repo, never()).consume(anyString(), anyString(), any());
    }

    /** Código que o banco não consome (inexistente, expirado, usado ou revogado) é recusado. */
    @Test
    void codigoNaoConsumidoPeloBancoEhRecusado() {
        InviteRepository repo = mock(InviteRepository.class);
        when(repo.consume(anyString(), anyString(), any())).thenReturn(0);
        assertThrows(ApiException.class, () -> new InviteService(repo).consume("ABCDE-FGHJK", "x"));
    }
}
