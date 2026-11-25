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
7. Responda de forma curta: o que foi lançado, o que ficou duplicado/pendente e eventuais erros.`;

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
      id: uuid, description: z.string().max(200).optional(), amount: z.number().optional(), date: date.optional(),
      status: status.optional(), categoryId: uuid.optional(), notes: z.string().max(1000).optional(),
      walletId: uuid.optional(), consumesLimit: z.boolean().optional(),
    },
    annotations: write,
  }, async ({ id, ...changes }) => call(async () => {
    const t = await get<Json>(`/transactions/${id}`);
    const body = {
      kind: t.kind, status: t.status, date: t.date, description: t.description, amount: t.amount,
      walletId: t.cardId && t.kind !== 'PAGAMENTO_FATURA' ? null : t.walletId, targetWalletId: t.targetWalletId,
      categoryId: t.categoryId, notes: t.notes, cardId: t.kind === 'PAGAMENTO_FATURA' ? null : t.cardId,
      credit: t.cardId ? t.invoiceId != null : null, consumesLimit: t.consumesLimit, invoiceId: t.invoiceId,
      originalCurrency: t.originalCurrency, originalAmount: t.originalAmount, exchangeRate: t.exchangeRate, iof: t.iof,
      ...Object.fromEntries(Object.entries(changes).filter(([, v]) => v !== undefined)),
    };
    return compactTx(await api.request<Json>(pat, 'PUT', `/transactions/${id}`, body));
  }));

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
