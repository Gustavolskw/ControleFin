# API externa do ControleFin (para o Claude / MCP)

Base: `https://controlefin.gkodex.cloud/ext/v1` (local: `http://localhost:8091/ext/v1`).

- **Autenticação:** `Authorization: Bearer cf_...`, um token pessoal criado no site em **Meu perfil → Acesso do Claude**.
  - O token aparece uma única vez.
  - Validade: 30, 90, 180 ou 365 dias.
  - Permissão "Só consultar" (GET) ou "Ler e lançar".
  - Revogar no site corta o acesso na hora. Uma recuperação de senha revoga todos os tokens.
- **Não tem login, cadastro nem nada de usuário.** O usuário é sempre o dono do token e nunca vem da requisição.
- **Respostas** em JSON. Erros vêm como `{"message": "..."}`:

  | Código | Quando |
  |---|---|
  | 401 | token ausente, inválido, revogado ou vencido |
  | 403 | token só de leitura tentando gravar |
  | 404 | rota inexistente, ou registro que não é seu |
  | 400 | dado inválido |
  | 409 | conflito |

- **Limites:** 120 requisições/min por IP e corpo de até 25 MB.
- **Datas** em `AAAA-MM-DD`. **Valores** em reais com ponto decimal (`23.50`).

## Fluxo recomendado para o Claude

1. `GET /context`: traz contas e carteiras, cartões, limites (com faturas), categorias e recorrências, com os IDs que os lançamentos usam.
2. **Antes de lançar, sempre conferir duplicidade:** `GET /transactions/similar?kind=DESPESA&date=2026-11-24&amount=23.50&walletId=...` (ou `cardId=...`).
   - Se vier algo, pergunte ao usuário antes de lançar.
   - O servidor também confere, item a item, na importação.
3. `POST /imports` com o modo pedido pelo usuário:
   - **`CONFIRMADO`** (padrão): compras feitas; entram e já contam no saldo e na fatura.
   - **`ANALISAR`**: entram **pendentes**; o usuário revisa depois no computador, em *Importações*.
   - **`REMOVER`**: desfaz compras (estorno ou cancelamento). **Nunca apaga**:
     - o que ainda não fechou na fatura (ou tem data futura) é cancelado;
     - o que já foi cobrado vira **ESTORNO**.
4. Mande **sempre** os arquivos de origem (print, PDF da fatura, CSV, Excel) em `attachments`. Eles ficam guardados criptografados e ligados aos lançamentos.
5. Use `idempotencyKey` (ex.: hash do arquivo + data). Se a conexão cair e você reenviar, o resultado original volta (`replayed: true`) e nada é lançado de novo.

## Rotas

| Método | Rota | Permissão | O que faz |
|---|---|---|---|
| GET | `/context` | leitura | Contas/carteiras (com saldo), limites de crédito (uso, fatura aberta e a pagar), cartões, categorias, recorrências |
| GET | `/dashboard?month=AAAA-MM` | leitura | Saldo total, previsão, faturas a pagar, resumo do mês, próximos compromissos |
| GET | `/transactions?from=&to=&accountId=&walletId=&cardId=&creditLineId=&categoryId=&kind=&status=&q=` | leitura | Lançamentos (padrão: mês atual; até 1000) |
| GET | `/transactions/similar?kind=&date=&amount=&walletId=&cardId=` | leitura | Possíveis duplicados (mesmo tipo e valor, até 3 dias, mesma conta/cartão) |
| GET | `/transactions/{id}` | leitura | Um lançamento |
| PUT | `/transactions/{id}` | escrita | Edita (mesmo corpo de um item, com `kind`, `date`, `description`…) |
| POST | `/transactions/{id}/confirm` | escrita | Confirma um pendente |
| POST | `/transactions/{id}/reverse` | escrita | Estorna/cancela uma compra (corpo opcional `{"date": "AAAA-MM-DD"}`) |
| GET | `/recurrences/month?month=AAAA-MM` | leitura | Recorrências do mês: previsto × realizado |
| GET | `/credit-lines/{id}/invoices` | leitura | Faturas de um cartão de crédito |
| GET | `/invoices/{id}` | leitura | Fatura com compras, estornos e pagamentos |
| POST | `/imports` | escrita | Importa um lote (abaixo) |
| GET | `/imports` · `/imports/{id}` | leitura | Lotes enviados e o resultado de cada item |
| POST | `/imports/{id}/confirm` | escrita | Confirma os pendentes de um lote `ANALISAR` |
| POST | `/imports/{id}/undo` | escrita | Desfaz o lote (cancela o que ele criou; não vale para `REMOVER`) |
| POST | `/imports/{id}/attachments` | escrita | Anexa arquivos a um lote (`{"attachments": [{"name", "mimeType", "contentBase64"}]}`) |

### Registros auxiliares (consultar, criar e editar)

Mesmos corpos das telas do site. **Não há exclusão:** para tirar de uso, edite com `archived: true` (recorrência:
`active: false`). O `PUT` substitui o registro inteiro; envie todos os campos (o MCP faz isso sozinho, mudando só os
informados).

| Método | Rota | Permissão | O que faz |
|---|---|---|---|
| GET | `/accounts` · `/accounts/{id}` | leitura | Contas com carteiras e saldos |
| POST | `/accounts` | escrita | Cria conta (`name`, `type`, `holder` PF/PJ, `color`, `wallets: [{name, initialBalance}]`) |
| PUT | `/accounts/{id}` | escrita | Edita conta (`name`, `type`, `holder`, `color`, `archived`) |
| POST | `/accounts/{id}/wallets` | escrita | Cria carteira (`name`, `initialBalance`) |
| PUT | `/wallets/{id}` | escrita | Edita carteira (`name`, `initialBalance`, `archived`) |
| POST | `/wallets/{id}/adjust` | escrita | Ajusta o saldo: `{"balance", "date", "description"}` lança um `AJUSTE` com a diferença |
| GET | `/categories` | leitura | Categorias |
| POST · PUT | `/categories` · `/categories/{id}` | escrita | Cria/edita (`name`, `kind` RECEITA/DESPESA, `parentId`, `color`, `archived`) |
| GET | `/credit-lines` · `/credit-lines/{id}` | leitura | Limites de crédito: usado, disponível, reservado, faturas aberta e a pagar |
| POST | `/credit-lines` | escrita | Cria limite (`name`, `limit`, `closingDay`, `dueDay`, `paymentWalletId`, `color`, `cards: [{name, format, walletId}]`) |
| PUT | `/credit-lines/{id}` | escrita | Edita limite (mesmos campos, sem `cards`, com `archived`) |
| GET | `/cards` | leitura | Cartões |
| POST · PUT | `/cards` · `/cards/{id}` | escrita | Cria/edita (`name`, `format` FISICO/DIGITAL, `function` CREDITO_DEBITO/SO_DEBITO/VOUCHER_ALIMENTACAO, `creditLineId`, `walletId`, `archived`) |
| GET | `/recurrences` | leitura | Recorrências com a próxima data |
| POST · PUT | `/recurrences` · `/recurrences/{id}` | escrita | Cria/edita (`kind`, `description`, `amount`, `frequency`, `intervalCount`, `dayOfMonth`, `startDate`, `endDate`, `walletId` ou `cardId`+`credit`, `consumesLimit`, `categoryId`, moeda, `notes`, `active`) |
| POST | `/recurrences/{id}/occurrences/{AAAA-MM-DD}/skip` · `/unskip` | escrita | Pula / volta a prever uma ocorrência |
| GET | `/recurrences/{id}/occurrences/{AAAA-MM-DD}/candidates` | leitura | Lançamentos parecidos ainda sem vínculo (possível duplicado) |
| PUT | `/transactions/{id}/recurrence` | escrita | Liga o lançamento a uma ocorrência (`recurrenceId`, `recurrenceDate`; nulos desligam) |

### Investimentos

Caixinhas, CDB, LCI/LCA, Tesouro, poupança, fundos e previdência são **por valor**: rendem pelo indexador, com o CDI,
a Selic e o IPCA do Banco Central. Ações, FIIs, ETFs, BDRs e cripto são **por quantidade e preço**. Não há exclusão:
arquive com `archived: true`.

| Método | Rota | Permissão | O que faz |
|---|---|---|---|
| GET | `/investments` | leitura | Carteira: valor atual, investido, rendimento, proventos, renda fixa × variável e cada investimento |
| GET | `/investments/{id}` | leitura | Investimento com todos os movimentos |
| POST · PUT | `/investments` · `/investments/{id}` | escrita | Cria/edita (`name`, `type`, `ticker`, `indexer` CDI/SELIC/PRE/IPCA/POUPANCA/MANUAL, `rate`, `maturityDate`, `accountId`, `walletId`, `notes`, `archived`) |
| POST | `/investments/{id}/movements` | escrita | Movimento (abaixo) |
| PUT | `/investments/{id}/movements/{movementId}` | escrita | Corrige um movimento (o lançamento ligado na conta é refeito) |

**Taxa (`rate`) conforme o indexador (`indexer`):**

| Indexador | `rate` |
|---|---|
| CDI e SELIC | % do índice (`100` = 100% do CDI) |
| PRE | % ao ano |
| IPCA | taxa adicional, em % ao ano |
| POUPANCA e MANUAL | não usa |

**Movimentos** (`kind`):

| `kind` | Campos | Na conta (com `walletId`) |
|---|---|---|
| `APORTE` / `RESGATE` | `amount` | sai como `APLICACAO` / volta como `RESGATE` (não são despesa/receita) |
| `COMPRA` / `VENDA` | `quantity`, `unitPrice`, `fees` | sai quantidade × preço + taxas / volta quantidade × preço − taxas |
| `PROVENTO` | `amount` | entra como `RECEITA` |
| `ATUALIZACAO` | por valor: `amount` = saldo atual; por quantidade: `unitPrice` = cotação | não movimenta conta |

- **Sem `walletId`:** só registra no investimento, sem mexer nas contas (por exemplo, o saldo que já existia).
- **Duplicidade:** movimento igual (mesmo tipo e valor, até 3 dias) é recusado com 409, a não ser que venha `allowDuplicate: true`.
- **Lançamentos ligados:** só mudam pelo próprio movimento.
- **Data:** a data não pode ser futura.

Tudo o que é criado ou editado fica em **Meu perfil → Atividade recente**, com o nome do token.

## `POST /imports`

```json
{
  "mode": "CONFIRMADO",
  "idempotencyKey": "fatura-nubank-2026-12-sha256-ab12…",
  "note": "Fatura Nubank dezembro (PDF)",
  "attachments": [
    { "name": "fatura-dez.pdf", "mimeType": "application/pdf", "contentBase64": "JVBERi0xLjQK…" }
  ],
  "items": [
    { "kind": "DESPESA", "date": "2026-11-24", "description": "Padaria", "amount": 23.50,
      "walletId": "<carteira>", "categoryName": "Alimentação › Padaria" },
    { "date": "2026-11-20", "description": "Notebook", "amount": 3600, "cardId": "<cartão>", "credit": true,
      "installments": 12 },
    { "date": "2026-11-05", "description": "Claude Pro", "cardId": "<cartão>", "credit": true,
      "originalCurrency": "USD", "originalAmount": 20, "exchangeRate": 5.61, "iof": 3.93,
      "consumesLimit": false, "recurrenceId": "<recorrência>", "recurrenceDate": "2026-11-05" },
    { "kind": "PAGAMENTO_FATURA", "date": "2026-11-10", "description": "Pagamento fatura", "amount": 1500,
      "walletId": "<carteira>", "invoiceId": "<fatura>" }
  ]
}
```

### Campos de cada item (modos `CONFIRMADO` e `ANALISAR`)

| Campo | Observação |
|---|---|
| `kind` | `DESPESA` (padrão), `RECEITA`, `TRANSFERENCIA`, `AJUSTE`, `PAGAMENTO_FATURA`, `ESTORNO` |
| `date`, `description` | obrigatórios |
| `amount` | em reais; sem ele, com moeda estrangeira, é calculado: `originalAmount × exchangeRate + iof` |
| `walletId` | conta/carteira (Pix, dinheiro, boleto, débito automático) |
| `cardId` + `credit` | cartão; `credit: true` = crédito (vai para a fatura), `false` = débito/voucher (sai da carteira do cartão) |
| `installments` | parcelas (crédito, 1 a 72); `amount` é o **total** |
| `consumesLimit` | crédito: `false` = entra na fatura mas não ocupa limite (ex.: assinatura do Claude) |
| `invoiceId` | força a fatura (senão é escolhida pela data e pelo fechamento) |
| `categoryId` ou `categoryName` | nome como em `/context` (`"Pai › Filho"` ou só o nome); se não achar, lança sem categoria e avisa |
| `targetWalletId` | destino da transferência |
| `recurrenceId` + `recurrenceDate` | liga à ocorrência de uma recorrência (cada ocorrência só pode ser lançada uma vez) |
| `notes` | observações |
| `allowDuplicate` | `true` grava mesmo havendo lançamento igual (ex.: dois cafés iguais no mesmo dia) |
| `attachments` | índices dos anexos do lote para este item (padrão: todos) |

### Modo `REMOVER`

Cada item localiza a compra por `transactionId`, ou por `date` (±3 dias) + `amount` (+ `cardId`/`walletId`/`description` para desempatar). Se houver mais de uma compra possível, o item volta com erro listando os IDs.

### Resposta

```json
{ "id": "…", "mode": "CONFIRMADO", "replayed": false, "created": 3, "pending": 0, "duplicates": 1, "errors": 1,
  "removed": 0,
  "items": [ { "position": 0, "status": "CRIADO", "transactionId": "…", "message": null }, … ],
  "attachments": [ { "id": "…", "name": "fatura-dez.pdf", "size": 48211 } ] }
```

**Status de cada item:**

| Status | Significado |
|---|---|
| `CRIADO` | lançado |
| `PENDENTE` | lançado como pendente (modo `ANALISAR`) |
| `DUPLICADO` | já existia; nada foi gravado, e `transactionId` aponta o existente |
| `ERRO` | não lançado; `message` explica o motivo |
| `REMOVIDO` | cancelado (modo `REMOVER`) |
| `ESTORNADO` | estorno lançado (modo `REMOVER`) |
| `DESFEITO` | o lote foi desfeito |

- Cada item é independente: um erro não impede os outros.
- Anexos inválidos recusam o lote inteiro (400) antes de gravar qualquer coisa.

**Anexos aceitos** (até 10 MB cada, 20 MB por lote): PDF, PNG, JPG, WEBP, HEIC, CSV, TXT, OFX, XLS, XLSX. O conteúdo é conferido (um `.pdf` precisa ser PDF de verdade).

## Segurança (resumo)

- **Caminho da chamada:** Internet → Traefik (HTTPS) → nginx → api-externa → backend. O backend fica numa rede interna.
- A api-externa só repassa a lista de rotas acima. Ela leva o token junto com um segredo de serviço que nunca sai do servidor.
- O gateway bloqueia `/api/internal/` vindo de fora.
- **Quem valida é o backend:**
  - token: hash SHA-256, revogação, validade;
  - usuário ativo;
  - permissão de escrita.
- O PostgreSQL isola os dados por usuário (Row-Level Security).
- Tudo o que a API faz fica em **Meu perfil → Atividade recente**, com o nome do token usado.
