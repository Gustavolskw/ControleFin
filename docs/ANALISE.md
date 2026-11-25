# ControleFin — Análise inicial (gaps, banco de dados e arquitetura)

> Data: 2026-11-24 · Base de padrões: `../Chama&Essencia` (Spring Boot 3.4 / Java 21, Angular, nginx gateway,
> override Traefik, script `chama.sh`, auth por convite + BCrypt + perguntas de segurança + bloqueio).

---

## 1. Decisão de banco: **PostgreSQL** (recomendado)

| Critério | MongoDB (como no Chama&Essencia) | PostgreSQL |
|---|---|---|
| Transações em vários registros (transferência = 2 lançamentos; compra parcelada = N parcelas; pagar fatura) | Exige **replica set**. O compose de referência roda Mongo standalone → **sem transação multi-documento**. | ACID nativo, sem configuração extra. |
| Dinheiro exato | `Decimal128` existe, mas o Spring Data grava `BigDecimal` como **String** por padrão (precisa de conversor). Fácil de errar. | `NUMERIC(19,2)` ↔ `BigDecimal` direto. |
| Integridade (lançamento → conta/cartão/fatura/categoria) | Sem FK; integridade só no código. | FK, `CHECK`, `UNIQUE` (ex.: idempotência de importação). |
| Relatórios (por mês, categoria, cartão, PF/PJ, saldo consolidado) | Aggregation pipeline (funciona, mais verboso). | SQL `GROUP BY`, window functions, views. |
| Isolamento por usuário (dados privados) | Só filtro na aplicação. | Filtro na aplicação **+ Row-Level Security** como 2ª camada. |
| Evolução de esquema | Sem esquema (flexível, mas "deriva"). | Flyway com migrações versionadas. |
| Custo de trocar o padrão | Zero. | `pg_dump/pg_restore` no script de backup e no `PROD/pull-prod-db.sh`; JPA no lugar de Spring Data Mongo. |

**Veredito:** finanças são dados relacionais, monetários e transacionais. A ausência de transações no Mongo standalone
do padrão atual é decisiva. Vamos de **PostgreSQL 17 + Spring Data JPA + Flyway**. Todo o resto do padrão (gateway,
Traefik, script, segurança) é mantido.

---

## 2. Gaps na ideia (regras de negócio)

### 2.1 Contas e "saldo compartilhado"
- **Instituição × Conta/Carteira:** a Wiipo tem duas carteiras (VA e Livre); Nubank PF e Nubank PJ são contas
  distintas. Modelo: `Instituição 1—N Conta` (tipo: corrente, poupança, benefício, carteira, investimento, dinheiro).
- **"Saldo compartilhado" = visão consolidada** (soma das contas), não contas realmente compartilhadas. Faltando definir:
  - Vale-alimentação é dinheiro **restrito**. Entra no "total"? Sugestão: cada conta tem `incluiNoConsolidado` e
    o painel mostra **Livre** × **Restrito (benefícios)** × **Total**.
  - **PF × PJ:** misturar o caixa PJ com o pessoal distorce a "renda". Sugestão: titularidade PF/PJ e filtro no painel.
- **Receitas:** não foram citadas (salário, pró-labore, recarga do VA). Sem elas não existe "toda minha renda".
- **Saldo inicial e ajuste/conciliação** (saldo informado × saldo calculado).
- **Transferência entre contas próprias** não é receita nem despesa.

### 2.2 Cartões
- **Limite compartilhado:** cartões virtuais (e às vezes o adicional) usam o **mesmo limite** do físico.
  Modelo: `Conta de crédito (limite, dia de fechamento, dia de vencimento)` 1—N `Cartão (físico/virtual, final 4 dígitos, apelido)`.
- **Tipos:** crédito, débito, múltiplo, benefício (VA debita a carteira Wiipo, sem fatura).
- **Fatura:** a compra entra na fatura conforme o dia de fechamento; precisamos de faturas aberta/fechada/paga, pagamento
  parcial e saldo rolado.
- **Parcelado:** ocupa o limite **total** na compra e libera parcela a parcela; cada parcela cai numa fatura.
- **Pagamento de fatura é transferência** (conta → cartão), **não despesa**, senão a despesa conta duas vezes.
- **Estorno, chargeback, cashback, anuidade, IOF.**
- **Moeda estrangeira:** assinaturas de IA (Claude, ChatGPT etc.) costumam vir em USD com IOF e câmbio do fechamento.
  Precisamos de valor original + moeda + valor convertido + IOF.

### 2.3 Recorrentes ("não ocupam meu crédito livre") — **ambíguo, precisa de decisão**
Na prática, uma assinatura cobrada no cartão de crédito **ocupa o limite quando é cobrada**. Leituras possíveis:
1. **Reserva:** mostrar `Crédito livre = limite − usado − recorrências previstas até o fechamento` (as recorrências ficam
   "comprometidas" e saem da conta de quanto você ainda pode gastar). *← minha recomendação*
2. **Separação visual:** recorrências aparecem fora do "gasto livre" da fatura (orçamento à parte), mas consomem limite normalmente.
3. Elas estão em um cartão/limite separado (ex.: cartão virtual dedicado), que fica fora do limite principal.

Também é preciso definir:
- **Previsto × realizado:** a recorrência gera um lançamento *previsto*. Quando a cobrança real chega (via MCP),
  ela deve **casar** com o previsto e não duplicar.
- Valor variável (IA por uso), reajuste, frequência (mensal/anual), data de cancelamento.

### 2.4 Categorias, cupom fiscal e orçamento
- O fluxo do MCP fala em "analisar as compras do cupom fiscal". Isso pede **itens dentro do lançamento** (split por
  categoria: mercado → alimentação + limpeza), estabelecimento (nome/CNPJ) e categorias hierárquicas por usuário.
- Orçamento por categoria/mês e metas: fora do MVP, mas o modelo já deve suportar.

### 2.5 Importação via Claude: os riscos de dados ruins
- **Duplicidade:** o mesmo extrato enviado duas vezes (ou print + PDF da mesma fatura). Precisamos de:
  `Idempotency-Key` por requisição + detecção de duplicata natural (data + valor + cartão + descrição normalizada).
- **Erros de leitura do LLM:** importar como **lote** (`import_batch`), com status *pendente de revisão* ou
  *confirmado*, e **desfazer o lote inteiro**.
- **Resolução de entidades:** o Claude precisa traduzir "Nubank final 1234" → `cardId`. A API externa deve expor a
  consulta de contas, cartões e categorias, além da busca por final do cartão ou apelido.
- **Imagens:** o Claude lê o print/PDF (visão) e manda **JSON estruturado**. A API não faz OCR.
  Guardar o anexo original? (privacidade × rastreabilidade; recomendo **não** no MVP, só o hash).

### 2.6 Datas
- Fuso `America/Sao_Paulo`; data da compra × data de competência (fatura) × data de pagamento.

---

## 3. Gaps de arquitetura

### 3.1 Contradição: "a API principal não é pública" × o site Angular
O Angular roda **no navegador do usuário**, ou seja, fora da rede Docker. Se o backend aceitar só chamadas internas,
o site não consegue fazer login nem carregar dados. Resolução, igual à da referência:

```
Internet ─▶ Traefik ─▶ nginx (gateway, único exposto)
                          ├── /            ─▶ frontend (Angular estático)
                          ├── /api/        ─▶ backend      (sessão do site: JWT do login)
                          ├── /api/internal ─▶ DENY 404    (nunca roteado de fora)
                          └── /ext/v1/     ─▶ api-externa  (token pessoal de API, para o MCP)
                                                 │
                                                 └──(rede interna)──▶ backend /api/internal/**
backend ─▶ postgres          (backend, api-externa e postgres não publicam portas)
```

- O **backend nunca publica porta**. Só o gateway chega até ele, e apenas pelas rotas do site.
- Os endpoints que a api-externa consome ficam em `/api/internal/**`: o nginx bloqueia essa rota e o backend exige,
  além do token do usuário, um **segredo de serviço** (`INTERNAL_API_SECRET`, header) que só a api-externa conhece.
- A **api-externa não tem login, cadastro nem nada de usuário**, como você pediu. Ela só repassa o **token pessoal**
  ao backend, que valida o token e descobre o usuário. O backend continua sendo a única autoridade. Assim, mesmo que
  a api-externa seja comprometida, ela não consegue "escolher" o usuário.
- Domínio: `controlefin.<seu-dominio>` (DNS é minúsculo). A API externa no mesmo host, em `/ext/v1`, evita outro DNS
  e outro certificado. Se preferir, dá para usar um host separado (`api-controlefin.`).

### 3.2 Token de API (para o MCP)
- **Personal Access Token** gerado em *Meu perfil → Tokens de API*: mostrado uma única vez, guardado como hash SHA-256,
  com prefixo visível (`cfin_…abcd`), escopos (`read`, `write`), expiração, revogação e "último uso".
- O JWT do site **não** vale na api-externa, e o PAT **não** vale no `/api/` do site.
- Rate limit próprio no nginx para `/ext/` e limite de tamanho de payload.
- **Auditoria:** todo registro guarda `origem` (WEB / API / MCP), `tokenId` e `importBatchId`.

### 3.3 Atenção para a fase do MCP (não bloqueia agora)
- No app do Claude (celular/web), o conector MCP é **remoto**: o servidor MCP precisa estar acessível na internet por
  HTTPS. Pela documentação atual, os conectores personalizados do claude.ai autenticam com **OAuth**, não com
  "colar um token". Se for isso mesmo, o login/consentimento do OAuth ficaria no backend (o site), que emite o token
  usado pelo MCP. Isso mantém a regra de "api-externa sem login". **Confirmar na fase do MCP.** O modelo de tokens da
  seção 3.2 já serve como base.
- Onde o servidor MCP roda: um 4º container no mesmo compose (`mcp` → `api-externa`) é o caminho natural.

---

## 4. Gaps de segurança

| Item | Proposta |
|---|---|
| Dados privados por usuário | `user_id` em toda tabela, **nunca** vindo do corpo da requisição (sempre do token); repositórios sempre filtram por usuário; testes de IDOR (usuário A tentando ler ou alterar o id de B); **RLS do Postgres** como 2ª camada. |
| Quem pode convidar | Na referência, qualquer usuário convida e todos veem tudo. Aqui os dados são isolados. **Decidir:** qualquer usuário convida, ou só um papel ADMIN? O admin **não** vê dados financeiros de ninguém. |
| Perguntas de segurança | Mantidas (padrão da referência), mas são um fator fraco. Sugiro **TOTP (2FA) opcional**, já que são dados financeiros. |
| Revogação de sessão | Trocar a senha ou as perguntas invalida JWTs antigos (`tokenVersion` no usuário). |
| Cartões | Guardar **só os 4 últimos dígitos**. Nunca o número completo nem o CVV. |
| Armazenamento do JWT | Referência: token no front. Avaliar cookie `HttpOnly` + `SameSite=Strict`. |
| Headers | Manter os da referência + **HSTS** e **CSP**. |
| Backups | Contêm toda a vida financeira: **criptografar** (`age`/`gpg`) o arquivo do `pg_dump`. |
| LGPD | Exportar os próprios dados e excluir a conta (apaga tudo do usuário). |
| Logs | Não registrar valores, descrições nem tokens nos logs. |

---

## 5. Gaps de infraestrutura/desenvolvimento (adaptação do padrão)

- `chama.sh` → `controlefin.sh` (start/stop/restart/status/logs/update/backup/restore), com `pg_dump -Fc` e `pg_restore`.
- Nomes sem colisão no mesmo servidor: container `nginx-controlefin`, routers/serviço Traefik `controlefin`,
  volume `<pasta>_pg-data`. Portas standalone diferentes das do Chama&Essencia (8090/8453) → **8091/8454**.
- `.env` gerado com segredos: `POSTGRES_PASSWORD`, `JWT_SECRET`, `INTERNAL_API_SECRET`, `DEFAULT_USER(_PASSWORD)`.
- Estrutura: `backend/`, `external-api/` (Spring Boot enxuto, sem banco), `frontend/`, `docker/nginx/`, `PROD/` (fora do git).
- Dinheiro sempre `BigDecimal` / `NUMERIC(19,2)`, nunca `double`.
- O projeto ainda **não é um repositório git**: `git init` no início.

---

## 6. Modelo de dados (rascunho)

```
users, invites, api_tokens, audit_log
accounts (user_id, nome, tipo, titularidade PF/PJ, cor)        -- (sem tabela de instituição: a conta já é o banco)
wallets (user_id, account_id, nome [Livre, VA/VR, Principal], saldo_inicial)   -- saldo da conta = Σ carteiras
credit_lines (user_id, institution_id, limite, dia_fechamento, dia_vencimento, conta_pagamento_id)
cards (user_id, nome, formato FISICO/DIGITAL, funcao CREDITO_DEBITO/SO_DEBITO/VOUCHER_ALIMENTACAO,
       credit_line_id?, wallet_id (débito/voucher), ativo)
attachments (user_id, import_batch_id?, nome_original, mime, tamanho, sha256, caminho_criptografado)
transaction_attachments (transaction_id, attachment_id)
invoices (user_id, credit_line_id, competência, fechamento, vencimento, status, total, pago)
categories (user_id, parent_id, nome, tipo receita/despesa)
merchants (user_id, nome, cnpj?)
transactions (user_id, tipo receita/despesa/transferência/pagamento_fatura/estorno/ajuste,
              wallet_id | card_id + invoice_id, consome_limite, data, valor, moeda_original, valor_original, cotacao, iof,
              descrição, merchant_id, category_id, status previsto/confirmado/pendente_revisão,
              recurrence_id?, installment_group_id?, parcela n/N, import_batch_id?, origem, idempotency_key)
transaction_items (transaction_id, descrição, qtd, valor, category_id)   -- cupom fiscal
recurrences (user_id, descrição, valor/estimado, moeda, frequência, dia, card_id|wallet_id, category_id, início, fim, consome_limite)
import_batches (user_id, origem, token_id, status, criado_em, resumo)
```

---

## 7. Fases sugeridas

Situação: fases 0, 1, 2 e 3 **concluídas**.

0. **Infra + Auth:** monorepo, compose (postgres, backend, frontend, nginx, traefik), `controlefin.sh`,
   login/convite/perguntas/primeiro acesso/bloqueio (portado da referência) + isolamento por usuário.
1. **Contas:** instituições, contas, saldo inicial, categorias, receitas/despesas, transferências, painel consolidado.
2. **Cartões:** linhas de crédito, cartões físicos/virtuais, faturas, parcelamento, pagamento de fatura, moeda estrangeira.
   Implementado: limite compartilhado (`credit_lines`), cartões (`cards`), faturas (`invoices`, mês do vencimento;
   compra no dia do fechamento vai para a próxima), parcelas (uma linha por parcela, centavo da divisão na 1ª),
   `consumes_limit`, câmbio (moeda, valor, cotação, IOF), PAGAMENTO_FATURA (parcial ou total; não é despesa),
   ESTORNO (nunca apaga: cancela o que não fechou, devolve como crédito o que já foi cobrado), saldo credor levado
   para a fatura seguinte. Sem juros/rotativo: fatura não paga fica "Vencida" na própria fatura.
3. **Recorrências:** previsto × realizado, reserva de limite, painel de compromissos.
   Implementado: `recurrences` (mensal/anual/semanal, intervalo, dia, início/fim, mesmas opções de um lançamento:
   conta ou cartão crédito/débito, categoria, moeda estrangeira), ocorrências calculadas (não gravadas),
   `transactions.recurrence_id + recurrence_date` com índice único parcial (a mesma ocorrência não é lançada duas
   vezes; cancelado libera), `recurrence_skips` (pular mês), candidatos a duplicado (mesmo tipo, ±7 dias, ±25%,
   mesma conta/cartão ou descrição parecida) e vínculo de lançamento existente, `GET /api/transactions/similar`
   (checagem de duplicado antes de gravar), reserva de limite da próxima cobrança na fatura aberta e previsão do
   fim do mês com as recorrências em conta. Compras que não reservam limite ficam fora do cálculo do limite por
   inteiro (nem consomem o saldo credor que passa para a fatura seguinte).
4. **Tokens + API externa:** PAT, `external-api`, `/api/internal`, lotes de importação, idempotência, revisão e desfazer.
5. **MCP:** servidor MCP remoto + autenticação (OAuth, se confirmado).
6. Relatórios, orçamento, exportação LGPD, 2FA.

---

## 8. Decisões tomadas

- **Banco:** PostgreSQL 17 + JPA + Flyway.
- **Topologia:** o site acessa o backend pelo gateway nginx (`/api`); o backend não publica porta; só a api-externa
  atende o MCP (`/ext/v1`); `/api/internal/**` bloqueado no nginx + segredo de serviço (`INTERNAL_API_SECRET`).
- **Autenticação do site: mesmo padrão do Chama&Essencia.** JWT Bearer guardado no `localStorage` e enviado pelo
  interceptor; rate limit de 10/min por IP no nginx em `/api/auth/`; bloqueio de 15 min após 5 tentativas; BCrypt;
  3 perguntas de segurança; primeiro acesso obrigatório. Sem cookie `HttpOnly`, CSRF, Turnstile ou checagem de
  `Sec-Fetch-*`. Acesso via Postman com login válido é aceito (o usuário só vê os próprios dados).
- **Canais:** site ↔ somente backend; MCP ↔ somente api-externa. A api-externa **não valida nada**: repassa o token
  ao backend, que verifica se o usuário existe, está ativo e se o token não expirou nem foi revogado. Tráfego externo
  só por HTTPS (Traefik/Let's Encrypt + HSTS); api-externa → backend trafega só na rede Docker interna da própria máquina.
- **Contas com vários saldos:** uma conta pode ter **carteiras** com saldo e entradas próprias.
  Ex.: Wiipo → `Livre` e `VA/VR` (juntos). O saldo da conta é a soma das carteiras.
- **Saldo total:** card com o total de **todas** as contas (PF, PJ e benefícios entram na soma), com detalhamento
  por conta e por carteira.
- **Cartões:** cadastro com **nome**, **físico ou digital** e **função**: `Crédito e débito`, `Só débito` ou
  `Voucher alimentação`. Sem número do cartão. Cartões de crédito da mesma instituição **dividem o limite** (linha de crédito).
- **Limite e fatura:** funcionam como no banco: limite total, dia de fechamento e vencimento informados pelo usuário;
  compra parcelada ocupa o valor total e libera a cada parcela paga.
- **Pagamento de fatura** é um tipo próprio (`PAGAMENTO_FATURA`), não é despesa: sai da conta e abate a fatura.
- **Moeda estrangeira:** valor original, moeda, cotação, IOF e valor em reais são cadastrados.
- **Ocupa limite ou não:** escolha feita **no cadastro da compra ou da recorrência** (`consomeLimite`).
  Ex.: assinatura do Claude cai todo mês na fatura, mas não reduz o crédito livre.
  Recorrências têm as mesmas opções de um lançamento (conta, carteira ou cartão; categoria; moeda; parcelas).
- **Duplicidade:** antes de inserir, o Claude **sempre consulta** se o dado já existe (endpoint de verificação na
  api-externa). O backend também recusa duplicatas (`Idempotency-Key` + regra de dado igual: data, valor,
  cartão/conta e descrição).
- **Convites:** só o **admin** gera. O usuário padrão criado no primeiro start (como no Chama&Essencia) é o admin.
  O admin **não** tem acesso aos dados financeiros de nenhum usuário, nem aos próprios dados de outros.
- **Modo de importação:** informado pelo Claude em cada envio, conforme o que você pedir no chat:
  - `CONFIRMADO` (**padrão**): compra feita; salva e já entra no saldo/fatura.
  - `ANALISAR`: salva como pendente, **sem afetar saldo nem limite**, até você revisar e confirmar no computador.
  - `REMOVER`: pedido para desfazer uma compra (estorno ou cancelamento). O Claude primeiro localiza o lançamento
    pela consulta; o registro **não é apagado** (histórico e auditoria):
    - se ainda não entrou numa fatura fechada (ou é débito/conta) → status `CANCELADO`, sai do saldo/limite;
    - se já está numa fatura fechada ou paga → gera um lançamento de `ESTORNO` (crédito na fatura aberta), ligado à compra original.
  Todo envio gera um **lote** (com seus anexos) que pode ser desfeito inteiro.
- **Anexos:** prints, PDFs, CSV, Excel e qualquer outro arquivo enviado são **sempre guardados** e ligados aos
  registros por ID (`attachments` 1—N `import_batches` / lançamentos). Ficam em volume Docker próprio, criptografados
  (AES-GCM, chave no `.env`), com metadados e hash SHA-256 no Postgres. Entram no backup.
- **2FA:** fora do escopo por enquanto (implementação futura).
- **Datas/fuso:** análise posterior (padrão provisório: `America/Sao_Paulo`).
