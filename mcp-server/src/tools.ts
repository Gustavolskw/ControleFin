import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import * as z from 'zod';
import { ApiError, ExternalApi } from './api.js';
import { now, Sealer } from './seal.js';

/** Instruções entregues ao Claude ao conectar (regras de uso combinadas com o dono do sistema). */
export const INSTRUCTIONS = `ControleFin é o controle financeiro pessoal do usuário (contas, carteiras como Wiipo Livre e VA/VR, cartões de
crédito com limite compartilhado, faturas, parcelas, recorrências). Valores em reais (BRL), datas AAAA-MM-DD, fuso de
São Paulo.

Regras:
1. Comece com obter_contexto para saber os IDs de contas, carteiras, cartões, categorias e recorrências. Nunca invente IDs.
2. ANTES de lançar qualquer coisa, confira duplicidade com verificar_duplicados (e/ou listar_lancamentos). Se houver
   lançamento igual, mostre ao usuário e pergunte antes de lançar de novo. A importação também recusa duplicados
   (status DUPLICADO); só use allowDuplicate=true quando o usuário confirmar que é outra compra.
3. Modo da importação (pergunte se o usuário não disser): CONFIRMADO (padrão: compras feitas, já contam no saldo),
   ANALISAR (entra pendente para o usuário revisar no computador) ou REMOVER (desfaz compras canceladas/estornadas:
   cancela o que não foi cobrado e lança estorno do que já foi; nunca apaga).
4. Compras no cartão de crédito: use cardId + credit=true; parcelado = amount total + installments. Assinaturas que
   entram na fatura sem ocupar o limite (ex.: Claude): consumesLimit=false. Moeda estrangeira: originalCurrency,
   originalAmount, exchangeRate e iof (o valor em reais é calculado). Débito/voucher: cardId + credit=false.
   Pix/dinheiro/boleto: walletId. Pagamento de fatura: kind=PAGAMENTO_FATURA com walletId e invoiceId (não é despesa).
5. Os arquivos de origem (print, PDF da fatura, planilha) devem SEMPRE ser guardados: depois de importar, mostre ao
   usuário o link_para_anexos devolvido para ele enviar o arquivo pelo celular (vale 24 h). CSV/TXT/OFX que você
   tiver em texto podem ir direto em textAttachments.
6. Use uma idempotencyKey estável por envio (ex.: nome do arquivo + data) para que um reenvio não lance em dobro.
7. Cadastros (contas, carteiras, categorias, limites, cartões, recorrências): consulte a lista antes de criar para não
   duplicar; para mudar, use editar_* (só os campos informados mudam). Não existe exclusão: para tirar de uso, arquive
   (archived=true; recorrência: active=false). Saldo errado numa carteira: ajustar_saldo.
8. Não há ferramenta de login, cadastro de usuário, senha, perfil ou tokens: isso é só no site.
9. Responda de forma curta: o que foi lançado, o que ficou duplicado/pendente e eventuais erros.`;

const kind = z.enum(['DESPESA', 'RECEITA', 'TRANSFERENCIA', 'AJUSTE', 'PAGAMENTO_FATURA', 'ESTORNO']);
const status = z.enum(['CONFIRMADO', 'PENDENTE', 'CANCELADO']);
const date = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'data AAAA-MM-DD');
const month = z.string().regex(/^\d{4}-\d{2}$/, 'mês AAAA-MM');
const uuid = z.string().uuid();

const item = z.object({
  kind: kind.optional().describe('Tipo (padrão DESPESA)'),
  date: date.optional().describe('Data da compra/lançamento (obrigatória para criar)'),
  description: z.string().max(200).optional(),
  amount: z.number().optional().describe('Valor em reais; no parcelado, o TOTAL. Sem ele, calculado da moeda estrangeira'),
  walletId: uuid.optional().describe('Carteira (Pix, dinheiro, boleto, débito automático, pagamento de fatura)'),
  targetWalletId: uuid.optional().describe('Destino de uma TRANSFERENCIA'),
  cardId: uuid.optional(),
  credit: z.boolean().optional().describe('true = crédito (vai para a fatura); false = débito/voucher'),
  installments: z.number().int().min(1).max(72).optional(),
  consumesLimit: z.boolean().optional().describe('false = entra na fatura sem ocupar limite (ex.: assinatura do Claude)'),
  invoiceId: uuid.optional().describe('Fatura (obrigatória no PAGAMENTO_FATURA; opcional para forçar a fatura da compra)'),
  categoryId: uuid.optional(),
  categoryName: z.string().max(130).optional().describe('Alternativa ao categoryId: "Pai › Filho" ou só o nome'),
  notes: z.string().max(1000).optional(),
  originalCurrency: z.string().length(3).optional().describe('Ex.: USD'),
  originalAmount: z.number().optional(),
  exchangeRate: z.number().optional(),
  iof: z.number().optional(),
  recurrenceId: uuid.optional().describe('Liga à ocorrência de uma recorrência (com recurrenceDate)'),
  recurrenceDate: date.optional(),
  allowDuplicate: z.boolean().optional().describe('Só com confirmação do usuário'),
  transactionId: uuid.optional().describe('Modo REMOVER: id da compra (ou informe date + amount)'),
});

type Json = Record<string, unknown>;

/**
 * Ferramentas = as rotas da API externa (paridade testada), nada além.
 * REGRA: só gestão financeira. Nunca login, cadastro, senha, perfil, tokens ou configuração/edição de usuário.
 */
export function buildServer(api: ExternalApi, sealer: Sealer, publicUrl: string, pat: string): McpServer {
  const server = new McpServer({ name: 'controlefin', title: 'ControleFin', version: '1.0.0' },
    { instructions: INSTRUCTIONS });

  const call = async (fn: () => Promise<unknown>) => {
    try {
      const data = await fn();
      return { content: [{ type: 'text' as const, text: JSON.stringify(data) }] };
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : 'Erro inesperado ao falar com o ControleFin.';
      return { content: [{ type: 'text' as const, text: msg }], isError: true };
    }
  };
  const get = <T = Json>(path: string, query?: Json) => api.request<T>(pat, 'GET', path, undefined, query);
  const post = <T = Json>(path: string, body?: unknown) => api.request<T>(pat, 'POST', path, body ?? {});
  const uploadLink = (batchId: string) =>
    `${publicUrl}/anexos/${sealer.seal('upload', { pat, b: batchId, exp: now() + 24 * 3600 })}`;

  const read = { readOnlyHint: true, openWorldHint: false };
  const write = { readOnlyHint: false, destructiveHint: false, openWorldHint: false };

  server.registerTool('obter_contexto', {
    title: 'Contas, cartões, categorias e recorrências',
    description: 'Lista contas e carteiras (com saldo), cartões de crédito (limite disponível, fatura aberta), cartões de '
      + 'débito/voucher, categorias e recorrências, com os IDs para usar nos lançamentos. Chame antes de lançar.',
    annotations: read,
  }, async () => call(async () => compactContext(await get('/context'))));

  server.registerTool('ver_painel', {
    title: 'Painel do mês',
    description: 'Saldo total, previsão no fim do mês, faturas a pagar, receitas/despesas do mês por categoria e próximos compromissos.',
    inputSchema: { month: month.optional().describe('AAAA-MM (padrão: mês atual)') },
    annotations: read,
  }, async ({ month }) => call(async () => {
    const d = await get<Json>('/dashboard', { month });
    const { accounts: _a, creditLines, ...rest } = d as Json & { accounts: unknown; creditLines: Json[] };
    return { ...rest, creditLines: (creditLines ?? []).map(l => ({ id: l.id, name: l.name, available: l.available, toPay: l.toPay })) };
  }));

  server.registerTool('listar_lancamentos', {
    title: 'Buscar lançamentos',
    description: 'Lista lançamentos por período e filtros (padrão: mês atual). Use para conferir antes de lançar ou para responder perguntas.',
    inputSchema: {
      from: date.optional(), to: date.optional(), accountId: uuid.optional(), walletId: uuid.optional(),
      cardId: uuid.optional(), creditLineId: uuid.optional(), categoryId: uuid.optional(), kind: kind.optional(),
      status: status.optional(), q: z.string().max(100).optional().describe('Texto na descrição/observação'),
    },
    annotations: read,
  }, async (args) => call(async () => (await get<Json[]>('/transactions', args)).map(compactTx)));

  server.registerTool('verificar_duplicados', {
    title: 'Verificar duplicidade',
    description: 'Procura lançamentos do mesmo tipo e valor até 3 dias da data, na mesma carteira/cartão. Use SEMPRE antes de lançar.',
    inputSchema: { kind, date, amount: z.number(), walletId: uuid.optional(), cardId: uuid.optional() },
    annotations: read,
  }, async (args) => call(async () => {
    const list = (await get<Json[]>('/transactions/similar', args)).map(compactTx);
    return { duplicados: list.length, lancamentos: list };
  }));

  server.registerTool('importar_lancamentos', {
    title: 'Lançar / importar',
    description: 'Lança um ou mais lançamentos (ou remove compras, no modo REMOVER). Confere duplicidade item a item. '
      + 'Devolve o resultado de cada item e um link_para_anexos para o usuário enviar o print/PDF de origem.',
    inputSchema: {
      mode: z.enum(['CONFIRMADO', 'ANALISAR', 'REMOVER']).default('CONFIRMADO'),
      note: z.string().max(500).optional().describe('Origem, ex.: "Fatura Nubank dezembro (PDF)"'),
      idempotencyKey: z.string().max(100).optional(),
      items: z.array(item).min(1).max(300),
      textAttachments: z.array(z.object({
        name: z.string().max(200).describe('Nome com extensão .csv, .txt ou .ofx'),
        content: z.string().max(5_000_000),
      })).max(5).optional().describe('Arquivos de texto de origem (CSV/TXT/OFX) para guardar junto'),
    },
    annotations: write,
  }, async ({ mode, note, idempotencyKey, items, textAttachments }) => call(async () => {
    const attachments = (textAttachments ?? []).map(a => ({
      name: a.name, contentBase64: Buffer.from(a.content, 'utf8').toString('base64'),
    }));
    const r = await post<Json & { id: string; items: Json[] }>('/imports', { mode, note, idempotencyKey, items, attachments });
    return { ...r, link_para_anexos: uploadLink(r.id),
      orientacao: 'Mostre o link_para_anexos ao usuário para ele enviar o print/PDF de origem (vale 24 h).' };
  }));

  server.registerTool('editar_lancamento', {
    title: 'Editar lançamento',
    description: 'Altera campos de um lançamento (só os informados). Em compra parcelada, muda descrição, categoria, '
      + 'observações e situação de todas as parcelas.',
    inputSchema: {
      id: uuid, kind: kind.optional(), description: z.string().max(200).optional(), amount: z.number().optional(),
      date: date.optional(), status: status.optional(), categoryId: uuid.optional(),
      notes: z.string().max(1000).optional(),
      walletId: uuid.optional().describe('Trocar para carteira (Pix, dinheiro...): deixa de ser compra no cartão'),
      targetWalletId: uuid.optional(),
      cardId: uuid.optional().describe('Trocar para cartão (com credit)'), credit: z.boolean().optional(),
      consumesLimit: z.boolean().optional(), invoiceId: uuid.optional(),
      originalCurrency: z.string().length(3).optional(), originalAmount: z.number().optional(),
      exchangeRate: z.number().optional(), iof: z.number().optional(),
    },
    annotations: write,
  }, async ({ id, ...changes }) => call(async () => {
    const t = await get<Json>(`/transactions/${id}`);
    const set = Object.fromEntries(Object.entries(changes).filter(([, v]) => v !== undefined));
    const base: Json = {
      kind: t.kind, status: t.status, date: t.date, description: t.description, amount: t.amount,
      walletId: t.cardId && t.kind !== 'PAGAMENTO_FATURA' ? null : t.walletId, targetWalletId: t.targetWalletId,
      categoryId: t.categoryId, notes: t.notes, cardId: t.kind === 'PAGAMENTO_FATURA' ? null : t.cardId,
      credit: t.cardId ? t.invoiceId != null : null, consumesLimit: t.consumesLimit, invoiceId: t.invoiceId,
      originalCurrency: t.originalCurrency, originalAmount: t.originalAmount, exchangeRate: t.exchangeRate, iof: t.iof,
    };
    // Trocar o meio de pagamento: carteira e cartão são exclusivos (fora o pagamento de fatura).
    if ('walletId' in set && !('cardId' in set) && (set.kind ?? base.kind) !== 'PAGAMENTO_FATURA') {
      Object.assign(base, { cardId: null, credit: null, invoiceId: null });
    }
    if ('cardId' in set && !('walletId' in set)) Object.assign(base, { walletId: null, invoiceId: null });
    return compactTx(await api.request<Json>(pat, 'PUT', `/transactions/${id}`, { ...base, ...set }));
  }));

  server.registerTool('ver_lancamento', {
    title: 'Detalhe de um lançamento',
    description: 'Todos os dados de um lançamento pelo ID (parcela, fatura, moeda estrangeira, recorrência, lote de origem).',
    inputSchema: { id: uuid },
    annotations: read,
  }, async ({ id }) => call(() => get(`/transactions/${id}`)));

  server.registerTool('confirmar_lancamento', {
    title: 'Confirmar pendente',
    description: 'Confirma um lançamento pendente (passa a contar no saldo/fatura).',
    inputSchema: { id: uuid },
    annotations: write,
  }, async ({ id }) => call(async () => compactTx(await post<Json>(`/transactions/${id}/confirm`))));

  server.registerTool('estornar_lancamento', {
    title: 'Estornar / cancelar compra',
    description: 'Desfaz uma compra sem apagar: cancela o que ainda não foi cobrado e lança estorno do que já foi.',
    inputSchema: { id: uuid, date: date.optional().describe('Data do estorno (padrão: hoje)') },
    annotations: { ...write, destructiveHint: true },
  }, async ({ id, date }) => call(async () => compactTx(await post<Json>(`/transactions/${id}/reverse`, { date }))));

  server.registerTool('recorrencias_do_mes', {
    title: 'Recorrências do mês',
    description: 'Previsto × realizado das recorrências (assinaturas, aluguel, salário). Ocorrências PREVISTA/ATRASADA ainda '
      + 'não foram lançadas: lance com recurrenceId + recurrenceDate para não duplicar.',
    inputSchema: { month: month.optional() },
    annotations: read,
  }, async ({ month }) => call(async () => {
    const m = await get<Json & { recurrences: unknown }>('/recurrences/month', { month });
    const { recurrences: _r, ...rest } = m;
    return rest;
  }));

  server.registerTool('listar_faturas', {
    title: 'Faturas de um cartão',
    description: 'Faturas de um cartão de crédito (creditLineId do contexto): situação, total, pago e a pagar.',
    inputSchema: { creditLineId: uuid },
    annotations: read,
  }, async ({ creditLineId }) => call(() => get(`/credit-lines/${creditLineId}/invoices`)));

  server.registerTool('ver_fatura', {
    title: 'Itens de uma fatura',
    description: 'Compras, estornos e pagamentos de uma fatura.',
    inputSchema: { invoiceId: uuid },
    annotations: read,
  }, async ({ invoiceId }) => call(async () => {
    const d = await get<{ invoice: Json; items: Json[] }>(`/invoices/${invoiceId}`);
    return { invoice: d.invoice, items: d.items.map(compactTx) };
  }));

  server.registerTool('listar_importacoes', {
    title: 'Importações enviadas',
    description: 'Últimos lotes enviados (resumo: lançados, pendentes, duplicados, erros).',
    annotations: read,
  }, async () => call(async () => (await get<Json[]>('/imports')).map(({ items: _i, ...b }) => b)));

  server.registerTool('ver_importacao', {
    title: 'Detalhe de uma importação',
    description: 'Resultado de cada item de um lote.',
    inputSchema: { id: uuid },
    annotations: read,
  }, async ({ id }) => call(() => get(`/imports/${id}`)));

  server.registerTool('confirmar_importacao', {
    title: 'Confirmar pendentes de um lote',
    description: 'Confirma todos os lançamentos pendentes de um lote enviado no modo ANALISAR.',
    inputSchema: { id: uuid },
    annotations: write,
  }, async ({ id }) => call(() => post(`/imports/${id}/confirm`)));

  server.registerTool('desfazer_importacao', {
    title: 'Desfazer importação',
    description: 'Cancela tudo o que um lote criou (o histórico fica). Use só com confirmação do usuário.',
    inputSchema: { id: uuid },
    annotations: { ...write, destructiveHint: true },
  }, async ({ id }) => call(() => post(`/imports/${id}/undo`)));

  server.registerTool('anexar_texto_a_importacao', {
    title: 'Guardar arquivo de texto num lote',
    description: 'Guarda arquivos de origem em texto (CSV/TXT/OFX) num lote já importado. Prints e PDFs vão pelo '
      + 'link_para_anexos, porque o arquivo original fica com o usuário.',
    inputSchema: {
      importId: uuid,
      files: z.array(z.object({
        name: z.string().max(200).describe('Nome com extensão .csv, .txt ou .ofx'),
        content: z.string().max(5_000_000),
      })).min(1).max(5),
    },
    annotations: write,
  }, async ({ importId, files }) => call(async () => {
    const attachments = files.map(a => ({ name: a.name, contentBase64: Buffer.from(a.content, 'utf8').toString('base64') }));
    const { items: _i, ...batch } = await post<Json & { items: unknown }>(`/imports/${importId}/attachments`, { attachments });
    return batch;
  }));

  registerAuxTools(server, { call, get, post, put: (p, b) => api.request<Json>(pat, 'PUT', p, b) }, read, write);

  server.registerTool('link_para_anexos', {
    title: 'Link para enviar arquivos',
    description: 'Gera um novo link (24 h) para o usuário enviar prints/PDFs/planilhas de um lote já importado.',
    inputSchema: { importId: uuid },
    annotations: read,
  }, async ({ importId }) => call(async () => {
    await get(`/imports/${importId}`);
    return { link_para_anexos: uploadLink(importId) };
  }));

  return server;
}

// ------------------------------------------------------------------------------------------ registros auxiliares

type ToolResult = { content: { type: 'text'; text: string }[]; isError?: boolean };
interface Http {
  call: (fn: () => Promise<unknown>) => Promise<ToolResult>;
  get: <T = Json>(path: string, query?: Json) => Promise<T>;
  post: <T = Json>(path: string, body?: unknown) => Promise<T>;
  put: (path: string, body: unknown) => Promise<Json>;
}
type Hints = { readOnlyHint: boolean; openWorldHint: boolean; destructiveHint?: boolean };

const color = z.string().regex(/^#[0-9A-Fa-f]{6}$/, 'cor #RRGGBB');
const accountType = z.enum(['CORRENTE', 'POUPANCA', 'PAGAMENTO', 'BENEFICIO', 'INVESTIMENTO', 'DINHEIRO', 'OUTRO']);
const holder = z.enum(['PF', 'PJ']);
const cardFormat = z.enum(['FISICO', 'DIGITAL']);
const cardFunction = z.enum(['CREDITO_DEBITO', 'SO_DEBITO', 'VOUCHER_ALIMENTACAO']);
const frequency = z.enum(['SEMANAL', 'MENSAL', 'ANUAL']);
const day = z.number().int().min(1).max(31);

/** Só os campos informados (undefined = não mexe; null = limpa). */
const given = (o: Json) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined));

function find(list: Json[], id: string, what: string): Json {
  const x = list.find(i => i.id === id);
  if (!x) throw new ApiError(404, `${what} não encontrado(a). Confira o ID com obter_contexto.`);
  return x;
}

/** Carteira e cartão são exclusivos: trocar um limpa o outro. */
function payment(base: Json, set: Json) {
  if ('walletId' in set && set.walletId && !('cardId' in set)) Object.assign(base, { cardId: null, credit: null });
  if ('cardId' in set && set.cardId && !('walletId' in set)) Object.assign(base, { walletId: null });
}

/**
 * Consultar, criar e editar contas, carteiras, categorias, limites, cartões e recorrências.
 * Sem exclusão: tirar de uso = editar com archived=true (recorrência: active=false).
 */
function registerAuxTools(server: McpServer, h: Http, read: Hints, write: Hints) {
  const { call, get, post, put } = h;

  // contas e carteiras
  server.registerTool('listar_contas', {
    title: 'Contas e carteiras',
    description: 'Contas (banco, benefício, dinheiro...) com as carteiras e saldos, inclusive arquivadas.',
    annotations: read,
  }, async () => call(() => get('/accounts')));

  server.registerTool('ver_conta', {
    title: 'Detalhe de uma conta', description: 'Uma conta com as carteiras e saldos.',
    inputSchema: { id: uuid }, annotations: read,
  }, async ({ id }) => call(() => get(`/accounts/${id}`)));

  server.registerTool('criar_conta', {
    title: 'Criar conta',
    description: 'Cria uma conta. Informe as carteiras (ex.: Wiipo: "Livre" e "VA/VR"); sem carteiras, cria "Principal". '
      + 'Confira antes com listar_contas se ela já não existe.',
    inputSchema: {
      name: z.string().min(1).max(60), type: accountType, holder: holder.describe('PF (pessoal) ou PJ (empresa)'),
      color: color.optional(),
      wallets: z.array(z.object({ name: z.string().min(1).max(40), initialBalance: z.number() })).max(10).optional(),
    },
    annotations: write,
  }, async (args) => call(() => post('/accounts', args)));

  server.registerTool('editar_conta', {
    title: 'Editar conta',
    description: 'Altera nome, tipo, titular, cor ou arquiva (archived=true) uma conta. Só os campos informados mudam.',
    inputSchema: { id: uuid, name: z.string().min(1).max(60).optional(), type: accountType.optional(),
      holder: holder.optional(), color: color.optional(), archived: z.boolean().optional() },
    annotations: write,
  }, async ({ id, ...changes }) => call(async () => {
    const a = await get<Json>(`/accounts/${id}`);
    return put(`/accounts/${id}`, { name: a.name, type: a.type, holder: a.holder, color: a.color,
      archived: a.archived, ...given(changes) });
  }));

  server.registerTool('criar_carteira', {
    title: 'Criar carteira numa conta',
    description: 'Adiciona uma carteira (saldo separado) a uma conta existente.',
    inputSchema: { accountId: uuid, name: z.string().min(1).max(40), initialBalance: z.number().default(0) },
    annotations: write,
  }, async ({ accountId, ...body }) => call(() => post(`/accounts/${accountId}/wallets`, body)));

  server.registerTool('editar_carteira', {
    title: 'Editar carteira',
    description: 'Altera nome, saldo inicial ou arquiva (archived=true) uma carteira. Para corrigir o saldo atual, '
      + 'use ajustar_saldo.',
    inputSchema: { id: uuid, name: z.string().min(1).max(40).optional(), initialBalance: z.number().optional(),
      archived: z.boolean().optional() },
    annotations: write,
  }, async ({ id, ...changes }) => call(async () => {
    const wallets = (await get<Json[]>('/accounts')).flatMap(a => a.wallets as Json[]);
    const w = find(wallets, id, 'Carteira');
    return put(`/wallets/${id}`, { name: w.name, initialBalance: w.initialBalance, archived: w.archived,
      ...given(changes) });
  }));

  server.registerTool('ajustar_saldo', {
    title: 'Ajustar saldo da carteira',
    description: 'Informe o saldo real da carteira: o sistema lança um AJUSTE com a diferença.',
    inputSchema: { walletId: uuid, balance: z.number().describe('Saldo real hoje'), date: date.optional(),
      description: z.string().max(200).optional() },
    annotations: write,
  }, async ({ walletId, ...body }) => call(() => post(`/wallets/${walletId}/adjust`, body)));

  // categorias
  server.registerTool('listar_categorias', {
    title: 'Categorias', description: 'Categorias de receita e despesa (com a categoria pai), inclusive arquivadas.',
    annotations: read,
  }, async () => call(() => get('/categories')));

  server.registerTool('criar_categoria', {
    title: 'Criar categoria',
    description: 'Cria uma categoria (ou subcategoria, com parentId). Confira antes com listar_categorias.',
    inputSchema: { name: z.string().min(1).max(60), kind: z.enum(['RECEITA', 'DESPESA']), parentId: uuid.optional(),
      color: color.optional() },
    annotations: write,
  }, async (args) => call(() => post('/categories', args)));

  server.registerTool('editar_categoria', {
    title: 'Editar categoria',
    description: 'Altera nome, tipo, categoria pai, cor ou arquiva (archived=true). Só os campos informados mudam.',
    inputSchema: { id: uuid, name: z.string().min(1).max(60).optional(), kind: z.enum(['RECEITA', 'DESPESA']).optional(),
      parentId: uuid.nullable().optional().describe('null = vira categoria principal'), color: color.optional(),
      archived: z.boolean().optional() },
    annotations: write,
  }, async ({ id, ...changes }) => call(async () => {
    const c = find(await get<Json[]>('/categories'), id, 'Categoria');
    return put(`/categories/${id}`, { name: c.name, kind: c.kind, parentId: c.parentId, color: c.color,
      archived: c.archived, ...given(changes) });
  }));

  // limites de crédito e cartões
  server.registerTool('listar_limites', {
    title: 'Limites de crédito',
    description: 'Limites de crédito (um limite pode ser compartilhado por vários cartões): usado, disponível, '
      + 'reservado, fatura aberta e a pagar.',
    annotations: read,
  }, async () => call(() => get('/credit-lines')));

  server.registerTool('ver_limite', {
    title: 'Detalhe de um limite', description: 'Um limite de crédito com os cartões e as faturas aberta/a pagar.',
    inputSchema: { id: uuid }, annotations: read,
  }, async ({ id }) => call(() => get(`/credit-lines/${id}`)));

  server.registerTool('criar_limite', {
    title: 'Criar limite de crédito (cartão de crédito)',
    description: 'Cria um limite de crédito com fechamento e vencimento, já com os cartões que o compartilham '
      + '(sempre crédito+débito; walletId = carteira do débito). Confira antes com listar_limites.',
    inputSchema: {
      name: z.string().min(1).max(60), limit: z.number().min(0), closingDay: day, dueDay: day,
      paymentWalletId: uuid.optional().describe('Carteira que paga a fatura'), color: color.optional(),
      cards: z.array(z.object({ name: z.string().min(1).max(60), format: cardFormat, walletId: uuid.optional() }))
        .max(10).optional(),
    },
    annotations: write,
  }, async (args) => call(() => post('/credit-lines', args)));

  server.registerTool('editar_limite', {
    title: 'Editar limite de crédito',
    description: 'Altera nome, valor do limite, dias de fechamento/vencimento, carteira de pagamento, cor ou arquiva. '
      + 'Só os campos informados mudam.',
    inputSchema: { id: uuid, name: z.string().min(1).max(60).optional(), limit: z.number().min(0).optional(),
      closingDay: day.optional(), dueDay: day.optional(), paymentWalletId: uuid.nullable().optional(),
      color: color.optional(), archived: z.boolean().optional() },
    annotations: write,
  }, async ({ id, ...changes }) => call(async () => {
    const l = await get<Json>(`/credit-lines/${id}`);
    return put(`/credit-lines/${id}`, { name: l.name, color: l.color, limit: l.limit, closingDay: l.closingDay,
      dueDay: l.dueDay, paymentWalletId: l.paymentWalletId, archived: l.archived, ...given(changes) });
  }));

  server.registerTool('listar_cartoes', {
    title: 'Cartões', description: 'Todos os cartões (crédito+débito, só débito, voucher), inclusive arquivados.',
    annotations: read,
  }, async () => call(() => get('/cards')));

  server.registerTool('criar_cartao', {
    title: 'Criar cartão',
    description: 'Cria um cartão. CREDITO_DEBITO exige creditLineId (o limite que ele compartilha); SO_DEBITO e '
      + 'VOUCHER_ALIMENTACAO usam walletId (carteira debitada).',
    inputSchema: { name: z.string().min(1).max(60), format: cardFormat, function: cardFunction,
      creditLineId: uuid.optional(), walletId: uuid.optional() },
    annotations: write,
  }, async (args) => call(() => post('/cards', args)));

  server.registerTool('editar_cartao', {
    title: 'Editar cartão',
    description: 'Altera nome, formato, função, limite, carteira ou arquiva (archived=true). Só os campos informados mudam.',
    inputSchema: { id: uuid, name: z.string().min(1).max(60).optional(), format: cardFormat.optional(),
      function: cardFunction.optional(), creditLineId: uuid.nullable().optional(), walletId: uuid.nullable().optional(),
      archived: z.boolean().optional() },
    annotations: write,
  }, async ({ id, ...changes }) => call(async () => {
    const k = find(await get<Json[]>('/cards'), id, 'Cartão');
    return put(`/cards/${id}`, { name: k.name, format: k.format, function: k.function, creditLineId: k.creditLineId,
      walletId: k.walletId, archived: k.archived, ...given(changes) });
  }));

  // recorrências
  server.registerTool('listar_recorrencias', {
    title: 'Recorrências',
    description: 'Assinaturas, aluguel, salário e outras recorrências (ativas e inativas), com a próxima data.',
    annotations: read,
  }, async () => call(() => get('/recurrences')));

  const recurrenceFields = {
    kind: z.enum(['DESPESA', 'RECEITA']), description: z.string().min(1).max(200), amount: z.number().positive()
      .describe('Valor previsto em reais'),
    frequency, intervalCount: z.number().int().min(1).max(12).optional().describe('A cada N períodos (padrão 1)'),
    dayOfMonth: day.optional().describe('MENSAL/ANUAL (padrão: dia da data de início)'), startDate: date,
    endDate: date.optional(), walletId: uuid.optional(), cardId: uuid.optional(),
    credit: z.boolean().optional().describe('true = no crédito (fatura)'),
    consumesLimit: z.boolean().optional().describe('false = assinatura que entra na fatura sem ocupar o limite'),
    categoryId: uuid.optional(), originalCurrency: z.string().length(3).optional(), originalAmount: z.number().optional(),
    notes: z.string().max(1000).optional(),
  };

  server.registerTool('criar_recorrencia', {
    title: 'Criar recorrência',
    description: 'Cria uma recorrência (assinatura, aluguel, salário). Pagamento: walletId, ou cardId + credit. '
      + 'Confira antes com listar_recorrencias se ela já não existe.',
    inputSchema: recurrenceFields,
    annotations: write,
  }, async (args) => call(() => post('/recurrences', args)));

  const partial = Object.fromEntries(Object.entries(recurrenceFields).map(([k, v]) => [k, v.optional()]));
  server.registerTool('editar_recorrencia', {
    title: 'Editar recorrência',
    description: 'Altera uma recorrência (valor, dia, pagamento, fim...) ou desativa (active=false). Só os campos '
      + 'informados mudam; ocorrências já lançadas não mudam.',
    inputSchema: { id: uuid, ...partial, endDate: date.nullable().optional(), active: z.boolean().optional() },
    annotations: write,
  }, async ({ id, ...changes }) => call(async () => {
    const r = find(await get<Json[]>('/recurrences'), id, 'Recorrência');
    const base: Json = Object.fromEntries(['kind', 'description', 'amount', 'frequency', 'intervalCount', 'dayOfMonth',
      'startDate', 'endDate', 'walletId', 'cardId', 'credit', 'consumesLimit', 'categoryId', 'originalCurrency',
      'originalAmount', 'notes', 'active'].map(k => [k, r[k]]));
    const set = given(changes as Json);
    payment(base, set);
    return put(`/recurrences/${id}`, { ...base, ...set });
  }));

  server.registerTool('pular_ocorrencia', {
    title: 'Pular ocorrência',
    description: 'Marca que uma ocorrência não vai acontecer (ex.: assinatura suspensa naquele mês).',
    inputSchema: { recurrenceId: uuid, date },
    annotations: write,
  }, async ({ recurrenceId, date }) => call(async () => {
    await post(`/recurrences/${recurrenceId}/occurrences/${date}/skip`);
    return { ok: true, pulada: date };
  }));

  server.registerTool('retomar_ocorrencia', {
    title: 'Voltar a prever ocorrência', description: 'Desfaz o pular_ocorrencia.',
    inputSchema: { recurrenceId: uuid, date },
    annotations: write,
  }, async ({ recurrenceId, date }) => call(async () => {
    await post(`/recurrences/${recurrenceId}/occurrences/${date}/unskip`);
    return { ok: true, prevista: date };
  }));

  server.registerTool('candidatos_da_ocorrencia', {
    title: 'Lançamentos parecidos com a ocorrência',
    description: 'Lançamentos já feitos e sem vínculo que parecem ser aquela ocorrência (possível duplicado): '
      + 'vincule com vincular_a_recorrencia em vez de lançar de novo.',
    inputSchema: { recurrenceId: uuid, date },
    annotations: read,
  }, async ({ recurrenceId, date }) => call(async () =>
    (await get<Json[]>(`/recurrences/${recurrenceId}/occurrences/${date}/candidates`)).map(compactTx)));

  server.registerTool('vincular_a_recorrencia', {
    title: 'Vincular lançamento à recorrência',
    description: 'Liga um lançamento existente a uma ocorrência (recurrenceId + recurrenceDate). Sem os dois, desliga.',
    inputSchema: { transactionId: uuid, recurrenceId: uuid.optional(), recurrenceDate: date.optional() },
    annotations: write,
  }, async ({ transactionId, recurrenceId, recurrenceDate }) => call(async () =>
    compactTx(await put(`/transactions/${transactionId}/recurrence`,
      { recurrenceId: recurrenceId ?? null, recurrenceDate: recurrenceDate ?? null }))));
}

// ---------------------------------------------------------------------------------------------- formatos enxutos

/** Lançamento sem campos vazios (menos tokens). */
export function compactTx(t: Json): Json {
  const out: Json = {};
  const keys = ['id', 'date', 'kind', 'status', 'description', 'amount', 'accountName', 'walletName', 'walletId',
    'cardName', 'cardId', 'creditLineName', 'invoiceId', 'invoiceReference', 'categoryName', 'categoryId', 'notes',
    'installmentNumber', 'installmentCount', 'consumesLimit', 'originalCurrency', 'originalAmount', 'exchangeRate', 'iof',
    'recurrenceId', 'recurrenceDate', 'origin', 'importBatchId', 'reversalOf', 'targetWalletName'];
  for (const k of keys) {
    const v = t[k];
    if (v === null || v === undefined) continue;
    if (k === 'consumesLimit' && (v === true || !t.invoiceId)) continue;
    out[k] = v;
  }
  return out;
}

export function compactContext(c: Json): Json {
  const accounts = (c.accounts as Json[] ?? []).filter(a => !a.archived).map(a => ({
    id: a.id, name: a.name, type: a.type, holder: a.holder, balance: a.balance,
    wallets: (a.wallets as Json[]).filter(w => !w.archived).map(w => ({ id: w.id, name: w.name, balance: w.balance })),
  }));
  const creditLines = (c.creditLines as Json[] ?? []).filter(l => !l.archived).map(l => {
    const cur = l.currentInvoice as Json | null;
    return {
      id: l.id, name: l.name, limit: l.limit, available: l.available, closingDay: l.closingDay, dueDay: l.dueDay,
      paymentWalletId: l.paymentWalletId,
      currentInvoice: cur ? { id: cur.id, reference: cur.reference, total: cur.total, dueDate: cur.dueDate } : null,
      cards: (l.cards as Json[]).filter(k => !k.archived).map(k => ({ id: k.id, name: k.name, format: k.format,
        debitWalletId: k.walletId })),
    };
  });
  const debitCards = (c.cards as Json[] ?? []).filter(k => !k.archived && k.function !== 'CREDITO_DEBITO')
    .map(k => ({ id: k.id, name: k.name, function: k.function, walletId: k.walletId, walletName: k.walletName }));
  const cats = c.categories as Json[] ?? [];
  const byId = new Map(cats.map(x => [x.id, x]));
  const categories = cats.filter(x => !x.archived).map(x => ({
    id: x.id, kind: x.kind, name: x.parentId ? `${(byId.get(x.parentId) as Json | undefined)?.name ?? ''} › ${x.name}` : x.name,
  }));
  const recurrences = (c.recurrences as Json[] ?? []).filter(r => r.active).map(r => ({
    id: r.id, kind: r.kind, description: r.description, amount: r.amount, frequency: r.frequency,
    dayOfMonth: r.dayOfMonth, paidWith: r.paidWith, nextDate: r.nextDate, cardId: r.cardId, walletId: r.walletId,
    credit: r.credit, consumesLimit: r.consumesLimit, originalCurrency: r.originalCurrency, originalAmount: r.originalAmount,
  }));
  return { today: c.today, accounts, creditLines, debitCards, categories, recurrences };
}
