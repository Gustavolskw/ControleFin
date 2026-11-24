package com.controlefin.auth;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.controlefin.user.Role;
import com.controlefin.user.User;
import com.controlefin.user.UserRepository;

/**
 * Cria o administrador padrão (DEFAULT_USER) quando o banco não tem nenhum usuário. É com ele que se entra pela
 * primeira vez e se geram os convites (o cadastro sempre exige convite). A senha inicial vem do .env
 * (DEFAULT_USER_PASSWORD); no primeiro login o sistema exige trocar a senha e cadastrar as 3 perguntas de segurança.
 */
@Component
public class DefaultUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DefaultUserSeeder.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;

    public DefaultUserSeeder(UserRepository users, PasswordEncoder encoder,
                             @Value("${app.default-user.username:controlefin}") String username,
                             @Value("${app.default-user.password:}") String password) {
        this.users = users;
        this.encoder = encoder;
        this.username = username.trim().toLowerCase(Locale.ROOT);
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        boolean generated = password == null || password.isBlank();
        String initial = generated ? randomPassword() : password;
        User user = new User();
        user.setUsername(username);
        user.setRole(Role.ADMIN);
        user.setPasswordHash(encoder.encode(initial));
        user.setPasswordChangeRequired(true);
        users.save(user);
        if (generated) {
            log.warn("Administrador '{}' criado com a senha inicial gerada: {}  (defina DEFAULT_USER_PASSWORD no .env)",
                    username, initial);
        } else {
            log.info("Administrador '{}' criado com a senha inicial de DEFAULT_USER_PASSWORD.", username);
        }
    }

    private static String randomPassword() {
        byte[] bytes = new byte[9];
        new SecureRandom().nextBytes(bytes);
        return "Cf@" + HexFormat.of().formatHex(bytes);
    }
}
