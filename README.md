# ControleFin

Controle financeiro pessoal e **privado**, com **conector para o Claude** (consultar e lançar pelo chat, inclusive no
celular — veja [`docs/MCP.md`](docs/MCP.md)): contas bancárias com várias carteiras (ex.: Wiipo Livre e VA/VR), cartões
físicos e digitais com limite compartilhado, faturas, parcelamentos, gastos recorrentes e importação de extratos e
faturas pelo Claude (MCP).

**Stack:** Java 21 + Spring Boot 4 (API) · Angular 19 (web) · PostgreSQL 17 · nginx (gateway) · Docker Compose ·
Traefik (produção). Mesmo padrão de operação do Chama&Essencia.

> Análise, decisões e roadmap: [`docs/ANALISE.md`](docs/ANALISE.md).

## Telas

| Tela | O que faz |
|---|---|
| **Painel** | Saldo total de todas as contas (PF + PJ + benefícios), previsão no fim do mês, receitas/despesas do mês, saldo por conta e por carteira, despesas por categoria. |
| **Lançamentos** | Receitas, despesas (em conta, cartão de crédito, débito ou voucher), transferências e ajustes; parcelas, moeda estrangeira (cotação + IOF), "reserva o limite"; estornar; filtros por mês, conta, cartão, tipo, situação, categoria e texto. |
| **Contas** | Contas (corrente, digital, benefício…) com uma ou mais **carteiras** de saldo próprio (ex.: Wiipo "Livre" e "VA/VR"); ajuste de saldo pelo valor real do banco. |
| **Cartões** | Cartões de crédito (limite compartilhado pelos cartões físico e digitais, fechamento e vencimento), débito e voucher alimentação; limite usado/disponível; fatura aberta e fatura a pagar. |
| **Faturas** | Fatura por mês: compras, parcelas, estornos e pagamentos; pagar (parcial ou total) e estornar compras. |
| **Recorrências** | Assinaturas, aluguel, salário (mensal, anual ou semanal): previsto × realizado no mês, lançar cada ocorrência uma única vez, pular, vincular um lançamento já feito; aviso de possível duplicado. |
| **Importações** | Lotes enviados pelo Claude: lançados, pendentes (modo Analisar), duplicados ignorados, erros e arquivos enviados; confirmar pendentes ou desfazer a importação. |
| **Categorias** | Categorias de despesa/receita com subcategorias (padrões criados no primeiro acesso). |
| **Administração** | (só admin) convites e ativação de contas — sem acesso a dados financeiros. |
| **Meu perfil** | Troca de senha (encerra as outras sessões) e das perguntas de segurança; **Acesso do Claude**: tokens da API externa (criar, validade, só leitura, revogar) e atividade recente (site e Claude). |

## Regras de cálculo

- **Saldo da carteira** = saldo inicial + receitas − despesas ± ajustes − transferências enviadas + recebidas
  (só lançamentos **confirmados** com data até hoje). Saldo da conta = soma das carteiras; saldo total = soma das contas.
- **Transferência** entre carteiras próprias não é receita nem despesa. **Pendente** e **cancelado** não afetam saldo.
- **Previsão no fim do mês** inclui os lançamentos confirmados com data futura dentro do mês (ex.: assinatura dia 30).
- **Ajustar saldo**: informe o saldo real; o sistema lança um AJUSTE com a diferença.
- **Compra no crédito** não mexe no saldo: entra na fatura do mês pela data (no dia do fechamento ou depois, vai
  para a próxima). Conta como despesa do mês na data da compra (cada parcela na data dela).
- **Parcelado**: uma parcela por fatura; ocupa o limite inteiro e libera conforme as faturas são pagas.
- **Reserva o limite** desmarcado (ex.: assinatura do Claude): entra na fatura, mas não ocupa o limite.
- **Pagamento de fatura** sai da carteira e abate a fatura; não é despesa (as compras já contaram). Pago a mais vira
  crédito na fatura seguinte.
- **Estornar** nunca apaga: o que ainda não fechou é cancelado; o que já foi cobrado volta como ESTORNO (crédito na
  fatura aberta ou entrada na carteira, no débito).
- **Recorrência**: cada ocorrência vira no máximo um lançamento (o banco impede duplicar; cancelar libera). Antes de
  lançar, o sistema mostra lançamentos parecidos sem vínculo para vincular em vez de duplicar; lançamentos comuns
  também avisam quando já existe um igual (mesmo valor, até 3 dias, mesma conta/cartão).
- **Reserva de limite** da recorrência no crédito: a próxima cobrança ainda não lançada que cai na fatura aberta já
  ocupa limite ("reservado"). Desmarcado: não reserva nada, nem quando lançada.
- **Previsão no fim do mês** inclui recorrências em conta do mês ainda não lançadas (crédito vai para a fatura).
- **Faturas a pagar** (painel) = faturas fechadas não pagas + fatura aberta de cada cartão (sem parcelas futuras).
- Dinheiro sempre `NUMERIC(19,2)` / `BigDecimal`, arredondamento bancário (HALF_EVEN).

## Arquitetura

```
 Internet ─▶ [Traefik] ─▶ nginx (gateway: única porta exposta)
                            ├── /              ─▶ frontend      (Angular estático)
                            ├── /api/          ─▶ backend ×N    (site; JWT)
                            ├── /api/internal/ ─▶ 404           (bloqueado de fora, sempre)
                            ├── /mcp, /oauth/, /anexos/ ─▶ mcp-server (conector do Claude) ─▶ external-api
                            └── /ext/v1/       ─▶ external-api  (token pessoal de API)
                                                     │
                                                     └─(rede interna)─▶ backend /api/internal/** (segredo de serviço)
 backend ─▶ postgres      (rede "data", interna: só o backend enxerga o banco)
```

- **backend**: a API principal. Não publica porta; só o gateway (rotas do site) e a external-api (rotas internas)
  chegam nele. É a única autoridade sobre usuários e tokens.
- **mcp-server**: conector do Claude (MCP remoto + OAuth em que a credencial é o token pessoal). Sem banco; só
  conversa com a external-api. Passo a passo em [`docs/MCP.md`](docs/MCP.md).
- **external-api**: API pública para o MCP. Sem banco e sem nada de login/cadastro/usuário; repassa o token pessoal
  ao backend, que valida e resolve o usuário.
- O gateway sempre apaga o header `X-Internal-Secret` vindo de fora e responde 404 para `/api/internal/`.
- Contrato da API externa (rotas, importação, modos, anexos): [`docs/API_EXTERNA.md`](docs/API_EXTERNA.md).

## Segurança

- Login com JWT (mesmo padrão do Chama&Essencia); senhas e respostas de segurança com hash BCrypt; política de
  senha forte (backend `PasswordPolicy.java` e frontend `core/password.ts`).
- **Dados privados por usuário, em duas camadas**:
  1. aplicação: todo acesso usa o id do usuário do token (`CurrentUser`), nunca um id vindo da requisição;
  2. banco: **Row-Level Security** do PostgreSQL. A aplicação conecta com o usuário restrito `controlefin_app`
     (sem superusuário, sem permissão de alterar esquema) e informa o usuário logado em cada transação
     (`UserScopedTransactionManager`); sem ele nenhuma linha financeira é visível, e o banco recusa gravar
     dados com o `user_id` de outra pessoa. FKs compostas `(id, user_id)` impedem ligar registros de usuários
     diferentes. As migrações (Flyway) rodam com o dono do banco e recriam as permissões a cada start
     (`db/migration/afterMigrate.sql`).
- **Administrador padrão** (`DEFAULT_USER`, padrão `controlefin`): criado no primeiro start com a senha
  `DEFAULT_USER_PASSWORD` do `.env`. No primeiro login o sistema (frontend **e** backend) só libera o perfil até
  **trocar a senha** e **cadastrar as 3 perguntas de segurança**.
- **Cadastro só com convite**: só o administrador gera códigos (uso único, 48 h, guardados como hash) em
  *Administração*. O administrador **não** tem acesso aos dados financeiros dos outros usuários.
- 3 perguntas de segurança (padrão da Microsoft ou personalizadas). *Esqueci minha senha* pede o usuário e as 3
  respostas (sem diferenciar maiúsculas/acentos).
- Trocar/redefinir a senha **encerra todas as sessões** (versão do token); desativar um usuário também.
- 5 tentativas erradas de login/recuperação bloqueiam a conta por 15 min; o nginx limita `/api/auth/` a 10/min por IP.
- Headers: CSP restritiva (fontes servidas localmente, sem script inline), HSTS em HTTPS, `X-Frame-Options: DENY`.
- **API externa (Claude)**:
  - token pessoal (`cf_…`) guardado só como hash, com validade, escopo leitura/escrita e revogação imediata;
  - o backend valida o token em toda chamada;
  - a recuperação de senha revoga todos os tokens;
  - tudo fica no registro de atividade.
- **Anexos** (prints, PDFs, CSV, Excel):
  - criptografados no disco com AES-256-GCM (chave `ATTACHMENTS_KEY`);
  - tipo conferido pelo conteúdo;
  - entregues sempre como download.
- Backups criptografados (AES-256, chave `BACKUP_ENCRYPTION_KEY` do `.env`): banco e anexos.

## Produção (mesmo servidor do Chama&Essencia, outro subdomínio)

```bash
git clone <repo> controlefin && cd controlefin
chmod +x controlefin.sh
./controlefin.sh start        # cria o .env com segredos aleatórios na primeira vez
```

Atrás do Traefik (recomendado, compartilhado com os outros sistemas):

1. DNS: registro **A** de `controlefin.<seu-dominio>` para o IP da VPS.
2. No `.env`: `TRAEFIK_ENABLED=true`, `APP_DOMAIN=controlefin.<seu-dominio>` e a mesma `TRAEFIK_NETWORK` /
   `TRAEFIK_CERTRESOLVER` dos outros sistemas.
3. `./controlefin.sh start`.

Os nomes não colidem com o Chama&Essencia: container `nginx-controlefin`, routers/serviço Traefik `controlefin`,
volume `<pasta>_pg-data`. No modo standalone (sem Traefik) o gateway publica `8091`/`8454`.

| Comando | O que faz |
|---|---|
| `./controlefin.sh start` / `stop` / `restart` / `status` | Sobe, para, reinicia, mostra estado |
| `./controlefin.sh logs [nginx\|backend\|external-api\|mcp-server\|frontend\|postgres]` | Logs |
| `./controlefin.sh update` | backup + `git pull` + rebuild (as migrações do banco rodam sozinhas) |
| `./controlefin.sh backup` | `backups/controlefin-AAAAMMDD-HHMMSS.dump.enc` + `.anexos.tar.enc` (criptografados) |
| `./controlefin.sh restore <arquivo>` | Restaura um backup (substitui os dados) |

Backup diário: `0 3 * * * cd /caminho/controlefin && ./controlefin.sh backup >> backups/cron.log 2>&1`

> Guarde o `.env` (principalmente `BACKUP_ENCRYPTION_KEY`, `ATTACHMENTS_KEY` e `MCP_TOKEN_KEY`) fora do servidor: sem ele os backups e
> os anexos não abrem.

## Desenvolvimento local

```bash
docker compose up -d postgres     # exige o .env (copie de .env.example)
cd backend && mvn spring-boot:run  # DB_OWNER_PASSWORD (=POSTGRES_PASSWORD) e DB_APP_PASSWORD (=APP_DB_PASSWORD)
cd frontend && npm install && npm start    # http://localhost:4200, proxy /api -> 8080
```

Para o banco local ficar acessível ao `mvn spring-boot:run`, publique a porta só em `127.0.0.1` com um
`docker-compose.override.yml` (fora do git):

```yaml
services:
  postgres:
    ports: ["127.0.0.1:5432:5432"]
```

## Estrutura

```
backend/       auth (login, convites, perguntas) · admin · user · apitoken (tokens do Claude) · audit · finance (contas, carteiras, categorias, lançamentos,
               cartões, faturas, recorrências, importações, anexos, painel, rotas internas) · config (JWT, filtros, RLS) · db/migration (Flyway)
external-api/  API pública para o MCP (sem banco; repasse com lista fechada de rotas)
mcp-server/    Conector do Claude: MCP (Streamable HTTP), OAuth 2.1 com o token pessoal, link de envio de arquivos
frontend/      pages/ painel, lançamentos, contas, cartões, faturas, recorrências, importações, categorias, perfil, administração, login/cadastro/recuperação
docker/nginx/  gateway (TLS, rate limit, bloqueio de /api/internal, headers de segurança)
docs/          análise e decisões
```
