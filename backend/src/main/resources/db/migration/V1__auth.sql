-- Usuários, perguntas de segurança e convites.

CREATE TABLE users (
    id                       UUID PRIMARY KEY,
    username                 VARCHAR(40)  NOT NULL,
    password_hash            VARCHAR(100) NOT NULL,
    role                     VARCHAR(20)  NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    active                   BOOLEAN      NOT NULL DEFAULT TRUE,
    password_change_required BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_attempts    INT          NOT NULL DEFAULT 0,
    failed_recovery_attempts INT          NOT NULL DEFAULT 0,
    locked_until             TIMESTAMPTZ,
    -- Incrementa ao trocar/redefinir a senha: invalida todos os tokens emitidos antes.
    token_version            INT          NOT NULL DEFAULT 0,
    last_login_at            TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_users_username ON users (username);

-- Pergunta em texto; resposta apenas como hash BCrypt.
CREATE TABLE user_security_questions (
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    position    INT          NOT NULL,
    question    VARCHAR(200) NOT NULL,
    answer_hash VARCHAR(100) NOT NULL,
    PRIMARY KEY (user_id, position)
);

-- Código de convite de uso único; guardado só como hash SHA-256.
CREATE TABLE invites (
    id         UUID PRIMARY KEY,
    code_hash  VARCHAR(64) NOT NULL,
    created_by UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    used_by    VARCHAR(40),
    used_at    TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_invites_code_hash ON invites (code_hash);
