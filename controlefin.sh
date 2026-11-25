#!/usr/bin/env bash
# Gerencia o ControleFin (PostgreSQL + API + API externa + Web + gateway nginx) via Docker Compose.
# Uso: ./controlefin.sh {start|stop|restart|status|logs|update|backup|restore} [args]
set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"

# Git Bash (Windows, desenvolvimento): não converter valores como "/CN=localhost" em caminhos do Windows.
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'

BACKUP_DIR="backups"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

info()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok()    { printf '\033[1;32m✔\033[0m %s\n' "$*"; }
warn()  { printf '\033[1;33m!\033[0m %s\n' "$*"; }
fail()  { printf '\033[1;31m✘\033[0m %s\n' "$*" >&2; exit 1; }

# Arquivos do compose: com TRAEFIK_ENABLED=true entra o override do Traefik.
compose_files() {
  local files=(-f docker-compose.yml)
  if [[ "${TRAEFIK_ENABLED:-false}" == "true" ]]; then
    files+=(-f docker-compose.traefik.yml)
  fi
  printf '%s\n' "${files[@]}"
}

# docker compose (plugin v2) ou docker-compose (v1)
compose() {
  local files
  mapfile -t files < <(compose_files)
  if docker compose version >/dev/null 2>&1; then
    docker compose "${files[@]}" "$@"
  elif command -v docker-compose >/dev/null 2>&1; then
    docker-compose "${files[@]}" "$@"
  else
    fail "Docker Compose não encontrado. Instale o plugin: https://docs.docker.com/compose/install/linux/"
  fi
}

check_requirements() {
  command -v docker >/dev/null 2>&1 || fail "Docker não encontrado. Instale: https://docs.docker.com/engine/install/"
  docker info >/dev/null 2>&1 || fail "Sem acesso ao Docker. Inicie o serviço (sudo systemctl start docker) ou adicione seu usuário ao grupo docker."
  compose version >/dev/null
  if [[ "${TRAEFIK_ENABLED:-false}" == "true" ]]; then
    [[ -n "${APP_DOMAIN:-}" ]] || fail "TRAEFIK_ENABLED=true exige APP_DOMAIN no .env"
    docker network inspect "${TRAEFIK_NETWORK:-proxy}" >/dev/null 2>&1 \
      || fail "Rede do Traefik '${TRAEFIK_NETWORK:-proxy}' não existe. Veja as redes com: docker network ls"
  fi
}

random_secret() {
  # $1 = quantidade de bytes aleatórios
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$1"
  else
    head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}

ensure_env() {
  if [[ -f .env ]]; then
    return
  fi
  info "Criando .env com senhas aleatórias"
  umask 077
  cat > .env <<EOF
POSTGRES_USER=controlefin
POSTGRES_PASSWORD=$(random_secret 24)
# Usuário restrito que a aplicação usa (sujeito ao Row-Level Security); criado pelo backend.
APP_DB_PASSWORD=$(random_secret 24)
JWT_SECRET=$(random_secret 48)
# Segredo entre a api-externa e o backend (rotas /api/internal, nunca expostas).
INTERNAL_API_SECRET=$(random_secret 32)
# Chave dos backups criptografados (./controlefin.sh backup). Guarde uma cópia FORA do servidor:
# sem ela não é possível restaurar os backups.
BACKUP_ENCRYPTION_KEY=$(random_secret 32)
# Chave dos anexos (prints, PDFs, planilhas) criptografados no disco. Guarde junto com o .env.
ATTACHMENTS_KEY=$(random_secret 32)
# Chave do conector MCP do Claude (cifra os tokens OAuth). Trocar desconecta o Claude (basta reconectar).
MCP_TOKEN_KEY=$(random_secret 32)

# Administrador padrão: criado no primeiro start; é quem gera os convites.
# O primeiro login exige trocar esta senha e cadastrar as perguntas de segurança.
DEFAULT_USER=controlefin
DEFAULT_USER_PASSWORD=Cf@$(random_secret 8)

HTTP_PORT=8091
HTTPS_PORT=8454
NGINX_SELF_SIGNED_CERT_SUBJECT=/CN=localhost
NGINX_SELF_SIGNED_CERT_SAN=DNS:localhost
NGINX_FORCE_HTTPS=false
BACKEND_REPLICAS=1

# Traefik (veja o README): true = o Traefik do servidor encaminha APP_DOMAIN para o gateway
TRAEFIK_ENABLED=false
APP_DOMAIN=
TRAEFIK_NETWORK=proxy
TRAEFIK_CERTRESOLVER=letsencrypt
TRAEFIK_ENTRYPOINT_HTTPS=websecure
TRAEFIK_ENTRYPOINT_HTTP=web
EOF
  ok ".env criado (guarde este arquivo: sem ele não é possível acessar o banco nem restaurar backups)"
  load_env
  ok "Primeiro acesso: usuário ${DEFAULT_USER}, senha inicial ${DEFAULT_USER_PASSWORD} (troque no primeiro login)"
}

# Versões novas podem exigir segredos novos: acrescenta ao .env existente só o que faltar (sem mexer no resto).
ensure_env_keys() {
  local key value
  for key in APP_DB_PASSWORD INTERNAL_API_SECRET BACKUP_ENCRYPTION_KEY ATTACHMENTS_KEY MCP_TOKEN_KEY; do
    if ! grep -q "^${key}=" .env; then
      value=$(random_secret 32)
      umask 077
      printf '%s=%s\n' "$key" "$value" >> .env
      info "Adicionado ${key} ao .env"
    fi
  done
}

load_env() {
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
}

public_url() {
  if [[ "${TRAEFIK_ENABLED:-false}" == "true" ]]; then
    echo "https://${APP_DOMAIN}"
    return
  fi
  local ip port="${HTTP_PORT:-8091}"
  ip=$(hostname -I 2>/dev/null | awk '{print $1}' || true)
  [[ "$port" == "80" ]] && echo "http://${ip:-localhost}" || echo "http://${ip:-localhost}:${port}"
}

wait_until_ready() {
  local out=""
  info "Aguardando a API responder pelo gateway"
  for _ in $(seq 1 90); do
    # Testa de dentro do container do nginx (funciona com ou sem portas publicadas).
    # 401 = API no ar exigindo login.
    out=$(compose exec -T nginx sh -c \
      'wget -S -O /dev/null "http://127.0.0.1/api/account" 2>&1' || true)
    if grep -q ' 401' <<<"$out"; then
      ok "ControleFin no ar: $(public_url)"
      return
    fi
    sleep 2
  done
  fail "A API não respondeu pelo gateway. Veja os logs: ./controlefin.sh logs nginx (ou backend)"
}

cmd_start() {
  check_requirements
  ensure_env
  ensure_env_keys
  load_env
  # Endereço público usado pelo conector do Claude (OAuth e links de envio).
  if [[ -z "${MCP_PUBLIC_URL:-}" ]]; then
    MCP_PUBLIC_URL=$(public_url)
    export MCP_PUBLIC_URL
  fi
  info "Construindo e subindo os serviços (backend x${BACKEND_REPLICAS:-1})"
  compose up -d --build --remove-orphans --scale "backend=${BACKEND_REPLICAS:-1}"
  wait_until_ready
}

cmd_stop() {
  check_requirements
  compose down
  ok "Serviços parados (os dados continuam no volume do PostgreSQL)"
}

cmd_status() {
  check_requirements
  compose ps
}

cmd_logs() {
  check_requirements
  compose logs -f --tail=200 "$@"
}

cmd_update() {
  check_requirements
  if [[ -d .git ]]; then
    info "Atualizando o código (git pull)"
    git pull --ff-only
  fi
  # Backup antes de subir uma versão nova (as migrações do banco rodam no start do backend).
  if compose ps --status running postgres 2>/dev/null | grep -q postgres; then
    cmd_backup
  fi
  cmd_start
}

# Backup = pg_dump (formato custom) + anexos, ambos criptografados com AES-256 (chave BACKUP_ENCRYPTION_KEY do .env).
# Os anexos já ficam criptografados no disco (ATTACHMENTS_KEY); o .tar do backup é criptografado de novo.
cmd_backup() {
  check_requirements
  load_env
  [[ -n "${BACKUP_ENCRYPTION_KEY:-}" ]] || fail "Defina BACKUP_ENCRYPTION_KEY no .env"
  command -v openssl >/dev/null 2>&1 || fail "openssl não encontrado (necessário para criptografar o backup)"
  mkdir -p "$BACKUP_DIR"
  chmod 700 "$BACKUP_DIR"
  local stamp file files
  stamp=$(date +%Y%m%d-%H%M%S)
  file="${BACKUP_DIR}/controlefin-${stamp}.dump.enc"
  files="${BACKUP_DIR}/controlefin-${stamp}.anexos.tar.enc"
  info "Gerando backup em ${file}"
  compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d controlefin -Fc \
    | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass env:BACKUP_ENCRYPTION_KEY > "$file"
  chmod 600 "$file"
  info "Copiando anexos para ${files}"
  compose exec -T backend tar -C /data/attachments -cf - . \
    | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass env:BACKUP_ENCRYPTION_KEY > "$files"
  chmod 600 "$files"
  ok "Backup concluído (banco $(du -h "$file" | cut -f1), anexos $(du -h "$files" | cut -f1))"
}

cmd_restore() {
  local file="${1:-}"
  [[ -n "$file" && -f "$file" ]] || fail "Informe o arquivo: ./controlefin.sh restore backups/controlefin-AAAAMMDD-HHMMSS.dump.enc"
  check_requirements
  load_env
  read -r -p "Isto SUBSTITUI todos os dados atuais pelo backup ${file}. Continuar? [s/N] " answer
  [[ "$answer" =~ ^[sS]$ ]] || fail "Restauração cancelada"
  info "Parando backend e api-externa durante a restauração"
  compose stop backend external-api
  if [[ "$file" == *.enc ]]; then
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_ENCRYPTION_KEY < "$file"
  else
    cat "$file"
  fi | compose exec -T postgres pg_restore -U "$POSTGRES_USER" -d controlefin --clean --if-exists --no-owner
  local files="${file%.dump.enc}.anexos.tar.enc"
  if [[ "$file" == *.dump.enc && -f "$files" ]]; then
    info "Restaurando anexos de ${files}"
    openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass env:BACKUP_ENCRYPTION_KEY < "$files" \
      | compose run --rm --no-deps -T --entrypoint sh backend -c 'rm -rf /data/attachments/* && tar -C /data/attachments -xf -'
  else
    warn "Arquivo de anexos não encontrado ao lado do backup (${files}); anexos mantidos como estão."
  fi
  ok "Backup restaurado"
  cmd_start
}

usage() {
  cat <<EOF
Uso: ./controlefin.sh <comando>

  start            Cria o .env (se não existir), constrói e sobe tudo
  stop             Para os serviços (mantém os dados)
  restart          stop + start
  status           Mostra o estado dos containers
  logs [serviço]   Acompanha os logs (nginx, backend, external-api, frontend, postgres)
  update           Backup + git pull + rebuild
  backup           Backup criptografado do banco em ${BACKUP_DIR}/
  restore <arq>    Restaura um backup (substitui os dados)
EOF
}

case "${1:-}" in
  start)   cmd_start ;;
  stop)    cmd_stop ;;
  restart) cmd_stop; cmd_start ;;
  status)  cmd_status ;;
  logs)    shift; cmd_logs "$@" ;;
  update)  cmd_update ;;
  backup)  cmd_backup ;;
  restore) shift; cmd_restore "$@" ;;
  *)       usage; exit 1 ;;
esac
