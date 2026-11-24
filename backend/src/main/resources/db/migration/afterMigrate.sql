-- Callback do Flyway: roda a cada inicialização, depois das migrações (como o dono do banco).
--
-- Cria/atualiza o usuário que a APLICAÇÃO usa para acessar os dados. Ele não é superusuário nem dono das
-- tabelas, por isso o Row-Level Security vale para ele (superusuários ignoram RLS). Só tem permissão de
-- ler/gravar dados; não altera esquema.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${app_user}') THEN
        EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS',
                       '${app_user}', '${app_password}');
    ELSE
        EXECUTE format('ALTER ROLE %I WITH LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS',
                       '${app_user}', '${app_password}');
    END IF;
END $$;

GRANT CONNECT ON DATABASE controlefin TO "${app_user}";
GRANT USAGE ON SCHEMA public TO "${app_user}";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "${app_user}";
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO "${app_user}";
-- O histórico do Flyway é só do dono.
REVOKE ALL ON flyway_schema_history FROM "${app_user}";
