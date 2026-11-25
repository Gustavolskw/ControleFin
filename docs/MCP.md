# Conector do Claude (servidor MCP)

Com o conector, você fala com o Claude, no celular ou no computador, e ele consulta e lança no ControleFin. Por exemplo:

- "lança essa compra do print"
- "importa essa fatura em modo analisar"
- "cancela a compra do tênis"

Fluxo: **Claude → `https://controlefin.<seu-dominio>/mcp` (servidor MCP) → API externa → backend**. O servidor MCP só conversa com a API externa e não tem banco.

## Como conectar (uma vez)

1. **Publique o sistema com HTTPS.** O Claude só se conecta a um endereço público com HTTPS. Isso é o Traefik, com
   `TRAEFIK_ENABLED=true` e `APP_DOMAIN=controlefin.<seu-dominio>` no `.env`, e depois `./controlefin.sh start`.
   O endereço do conector passa a ser `https://controlefin.<seu-dominio>/mcp`.
   - Atrás de outro proxy, defina `MCP_PUBLIC_URL=https://...` no `.env`.
2. **Crie um token no site:** **Meu perfil → Acesso do Claude → Criar token**.
   - Escolha "Ler e lançar" ou "Só consultar" e a validade.
   - Copie o token: ele aparece uma vez só.
3. **No Claude (claude.ai):** **Configurações → Conectores → Adicionar conector personalizado**.
   - **URL:** `https://controlefin.<seu-dominio>/mcp`.
   - Deixe os campos de OAuth (Client ID/Secret) **vazios**: o Claude se registra sozinho (DCR).
4. **Clique em Conectar.** Abre a tela do ControleFin: cole o token e toque em **Autorizar**.
   - Pronto: o conector também aparece no app do celular, porque a conta é a mesma.

**Para desconectar:** revogue o token no site, ou remova o conector no Claude. Revogar o token corta o acesso na hora; ao trocar de token, reconecte.

## O que o Claude consegue fazer (ferramentas)

| Ferramenta | O que faz |
|---|---|
| `obter_contexto` | Contas e carteiras (com saldo), cartões e limites, faturas abertas, categorias, recorrências e os IDs |
| `ver_painel` | Saldo total, previsão, faturas a pagar, resumo do mês, próximos compromissos |
| `listar_lancamentos` | Busca por período, conta, cartão, categoria, tipo, situação e texto |
| `verificar_duplicados` | Procura lançamento igual (mesmo valor, até 3 dias, mesma conta ou cartão); é usada **antes de lançar** |
| `importar_lancamentos` | Lança um ou vários itens nos modos **CONFIRMADO** (padrão), **ANALISAR** (pendente) ou **REMOVER** (estorno/cancelamento). Cada item é conferido contra duplicidade. Devolve o **link para enviar o print/PDF** |
| `editar_lancamento` · `confirmar_lancamento` · `estornar_lancamento` | Ajustes em um lançamento |
| `recorrencias_do_mes` | Previsto × realizado (assinaturas, aluguel, salário) |
| `listar_faturas` · `ver_fatura` | Faturas de um cartão e os itens de cada uma |
| `listar_importacoes` · `ver_importacao` · `confirmar_importacao` · `desfazer_importacao` | Revisão dos lotes |
| `link_para_anexos` | Gera um novo link de envio para um lote |

O Claude recebe, ao conectar, as regras combinadas:

- usar os IDs do contexto;
- **sempre conferir duplicidade** antes de lançar;
- perguntar o modo da importação quando você não disser;
- usar `consumesLimit=false` em assinaturas que não ocupam o limite;
- moeda estrangeira com cotação e IOF;
- **sempre guardar o arquivo de origem**.

### Prints e PDFs

O Claude enxerga o print ou o PDF que você manda, mas o protocolo MCP não deixa ele repassar o arquivo em si. Por isso, toda importação devolve um **link de envio**:

- ele vale 24 h e serve só para anexar arquivos àquele lote;
- você toca no link, escolhe o mesmo print ou PDF e ele fica guardado, criptografado, junto com os lançamentos;
- CSV, TXT e OFX que o Claude tenha em texto vão direto na importação.

## Segurança

**Login sem senha nova.** O OAuth do conector não tem usuário/senha nem cadastro: a credencial é o seu token pessoal (`cf_…`), que o backend valida em toda chamada.

**Padrão OAuth seguido:**
- descoberta pelas RFC 9728 e 8414;
- registro dinâmico pela RFC 7591;
- **PKCE S256 obrigatório**;
- código de uso único que vence em 5 minutos;
- access token de 1 hora;
- refresh token de 30 dias com **rotação**: o refresh usado deixa de valer.

**Só o Claude recebe o retorno.** O registro aceita apenas `https://claude.ai/api/mcp/auth_callback`, `https://claude.com/api/mcp/auth_callback` e loopback (Claude Code). Um site de terceiros não consegue se passar pelo Claude para receber o código.

**Nada é gravado no servidor MCP.** Tokens e links são envelopes AES-256-GCM, com a chave `MCP_TOKEN_KEY` do `.env`. O token pessoal vai cifrado dentro deles, e cada tipo de envelope não serve no lugar de outro.

**Revogação:**
- revogou o token no site: as ferramentas avisam e o refresh falha, então o Claude pede para reconectar;
- trocar a `MCP_TOKEN_KEY` desconecta todos os conectores.

**Telas sem conteúdo ativo.** A autorização e o envio de arquivos não têm script. A CSP só permite voltar ao Claude, as páginas não podem ser abertas dentro de iframe, e há limite de requisições no gateway (`/oauth/`, `/anexos/`).

**Tudo fica registrado** em **Meu perfil → Atividade recente**, com o nome do token.

**Limitação conhecida:** os códigos e refresh tokens já usados ficam guardados em memória. Se o servidor MCP reiniciar durante a vida curta de um código (5 min), aquele código ainda poderia ser trocado uma segunda vez. Isso exige ter interceptado o código, e o PKCE impede quem não tem o `code_verifier` de usá-lo.

## Testar sem o Claude

```bash
curl -s https://controlefin.<seu-dominio>/.well-known/oauth-protected-resource
curl -s -X POST https://controlefin.<seu-dominio>/mcp -o /dev/null -w "%{http_code}\n"   # 401 = protegido
```

O MCP Inspector (`npx @modelcontextprotocol/inspector`) também se conecta pelo mesmo OAuth.
