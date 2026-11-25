/**
 * Perguntas de segurança padrão da Microsoft (conta local do Windows), em português.
 * O usuário também pode escrever uma pergunta personalizada.
 */
export const MICROSOFT_QUESTIONS = [
  'Qual era o nome do seu primeiro animal de estimação?',
  'Qual é o nome da cidade onde você nasceu?',
  'Qual era o seu apelido de infância?',
  'Qual é o nome da cidade onde seus pais se conheceram?',
  'Qual é o primeiro nome do seu primo mais velho?',
  'Qual é o nome da primeira escola que você frequentou?',
];

export type Role = 'ADMIN' | 'USER';

export interface QuestionAnswer {
  question: string;
  answer: string;
}

export interface Account {
  username: string;
  role: Role;
  securityQuestions: string[];
  passwordChangeRequired: boolean;
  setupRequired: boolean;
}

// ------------------------------------------------------------------ contas e carteiras

export type AccountType = 'CORRENTE' | 'POUPANCA' | 'PAGAMENTO' | 'BENEFICIO' | 'INVESTIMENTO' | 'DINHEIRO' | 'OUTRO';
export type Holder = 'PF' | 'PJ';

export const ACCOUNT_TYPES: { key: AccountType; label: string }[] = [
  { key: 'CORRENTE', label: 'Conta corrente' },
  { key: 'PAGAMENTO', label: 'Conta de pagamento (digital)' },
  { key: 'POUPANCA', label: 'Poupança' },
  { key: 'BENEFICIO', label: 'Benefício (VA/VR)' },
  { key: 'INVESTIMENTO', label: 'Investimento' },
  { key: 'DINHEIRO', label: 'Dinheiro' },
  { key: 'OUTRO', label: 'Outro' },
];

export function accountTypeLabel(t: AccountType): string {
  return ACCOUNT_TYPES.find(x => x.key === t)?.label ?? t;
}

export interface Wallet {
  id: string;
  accountId: string;
  name: string;
  initialBalance: number;
  balance: number;
  position: number;
  archived: boolean;
}

export interface BankAccount {
  id: string;
  name: string;
  type: AccountType;
  holder: Holder;
  color: string | null;
  archived: boolean;
  balance: number;
  wallets: Wallet[];
}

export interface WalletRequest {
  name: string;
  initialBalance: number;
}

export interface AccountRequest {
  name: string;
  type: AccountType;
  holder: Holder;
  color: string | null;
  archived: boolean;
  wallets?: WalletRequest[];
}

// ------------------------------------------------------------------ categorias

export type CategoryKind = 'RECEITA' | 'DESPESA';

export interface Category {
  id: string;
  name: string;
  kind: CategoryKind;
  parentId: string | null;
  parentName: string | null;
  color: string | null;
  archived: boolean;
}

export interface CategoryRequest {
  name: string;
  kind: CategoryKind;
  parentId: string | null;
  color: string | null;
  archived: boolean;
}

// ------------------------------------------------------------------ lançamentos

export type TransactionKind = 'RECEITA' | 'DESPESA' | 'TRANSFERENCIA' | 'AJUSTE' | 'PAGAMENTO_FATURA' | 'ESTORNO';
export type TransactionStatus = 'CONFIRMADO' | 'PENDENTE' | 'CANCELADO';

export const KIND_LABEL: Record<TransactionKind, string> = {
  RECEITA: 'Receita', DESPESA: 'Despesa', TRANSFERENCIA: 'Transferência', AJUSTE: 'Ajuste',
  PAGAMENTO_FATURA: 'Pagamento de fatura', ESTORNO: 'Estorno',
};
export const STATUS_LABEL: Record<TransactionStatus, string> = {
  CONFIRMADO: 'Confirmado', PENDENTE: 'Pendente', CANCELADO: 'Cancelado',
};

export interface Transaction {
  id: string;
  kind: TransactionKind;
  status: TransactionStatus;
  date: string;
  description: string;
  amount: number;
  walletId: string | null;
  walletName: string | null;
  accountId: string | null;
  accountName: string | null;
  targetWalletId: string | null;
  targetWalletName: string | null;
  targetAccountName: string | null;
  categoryId: string | null;
  categoryName: string | null;
  notes: string | null;
  origin: 'WEB' | 'API';
  createdAt: string;
  updatedAt: string;
  cardId: string | null;
  cardName: string | null;
  creditLineId: string | null;
  creditLineName: string | null;
  invoiceId: string | null;
  invoiceReference: string | null;
  consumesLimit: boolean;
  installmentGroup: string | null;
  installmentNumber: number | null;
  installmentCount: number | null;
  originalCurrency: string | null;
  originalAmount: number | null;
  exchangeRate: number | null;
  iof: number | null;
  reversalOf: string | null;
  recurrenceId: string | null;
  recurrenceDate: string | null;
  importBatchId: string | null;
}

export interface TransactionRequest {
  kind: TransactionKind;
  status: TransactionStatus;
  date: string;
  description: string;
  amount: number | null;
  walletId: string | null;
  targetWalletId: string | null;
  categoryId: string | null;
  notes: string | null;
  cardId?: string | null;
  credit?: boolean | null;
  installments?: number | null;
  consumesLimit?: boolean | null;
  invoiceId?: string | null;
  originalCurrency?: string | null;
  originalAmount?: number | null;
  exchangeRate?: number | null;
  iof?: number | null;
  recurrenceId?: string | null;
  recurrenceDate?: string | null;
}

export interface TransactionFilter {
  from?: string;
  to?: string;
  accountId?: string;
  walletId?: string;
  categoryId?: string;
  kind?: TransactionKind | '';
  status?: TransactionStatus | '';
  q?: string;
  cardId?: string;
  creditLineId?: string;
}

// ------------------------------------------------------------------ cartões, limites e faturas

export type CardFormat = 'FISICO' | 'DIGITAL';
export type CardFunction = 'CREDITO_DEBITO' | 'SO_DEBITO' | 'VOUCHER_ALIMENTACAO';
export type InvoiceStatus = 'ABERTA' | 'FUTURA' | 'FECHADA' | 'VENCIDA' | 'PAGA';

export const FORMAT_LABEL: Record<CardFormat, string> = { FISICO: 'Físico', DIGITAL: 'Digital' };
export const FUNCTION_LABEL: Record<CardFunction, string> = {
  CREDITO_DEBITO: 'Crédito e débito', SO_DEBITO: 'Só débito', VOUCHER_ALIMENTACAO: 'Voucher alimentação',
};
export const INVOICE_STATUS_LABEL: Record<InvoiceStatus, string> = {
  ABERTA: 'Aberta', FUTURA: 'Futura', FECHADA: 'Fechada', VENCIDA: 'Vencida', PAGA: 'Paga',
};

export interface Card {
  id: string;
  name: string;
  format: CardFormat;
  function: CardFunction;
  creditLineId: string | null;
  creditLineName: string | null;
  walletId: string | null;
  walletName: string | null;
  accountName: string | null;
  archived: boolean;
}

export interface CardRequest {
  name: string;
  format: CardFormat;
  function: CardFunction;
  creditLineId: string | null;
  walletId: string | null;
  archived: boolean;
}

export interface InvoiceSummary {
  id: string;
  creditLineId: string;
  creditLineName: string;
  reference: string;
  closingDate: string;
  dueDate: string;
  status: InvoiceStatus;
  total: number;
  paid: number;
  outstanding: number;
  nonConsuming: number;
  /** Crédito vindo da fatura anterior (pago a mais). */
  carriedCredit: number;
}

export interface InvoiceDetail {
  invoice: InvoiceSummary;
  items: Transaction[];
}

export interface CreditLine {
  id: string;
  name: string;
  color: string | null;
  limit: number;
  closingDay: number;
  dueDay: number;
  paymentWalletId: string | null;
  paymentWalletName: string | null;
  archived: boolean;
  used: number;
  available: number;
  cards: Card[];
  currentInvoice: InvoiceSummary | null;
  dueInvoice: InvoiceSummary | null;
  toPay: number;
  /** Recorrências no crédito que reservam limite e ainda vão cair na fatura aberta. */
  reserved: number;
}

export interface CreditLineRequest {
  name: string;
  color: string | null;
  limit: number;
  closingDay: number;
  dueDay: number;
  paymentWalletId: string | null;
  archived: boolean;
  cards?: { name: string; format: CardFormat; walletId: string | null }[];
}

// ------------------------------------------------------------------ painel

export interface CategoryTotal {
  categoryId: string | null;
  name: string;
  amount: number;
}

export interface MonthSummary {
  month: string;
  income: number;
  expense: number;
  result: number;
  expensesByCategory: CategoryTotal[];
  incomeByCategory: CategoryTotal[];
}

export interface Dashboard {
  asOf: string;
  total: number;
  totalPf: number;
  totalPj: number;
  projectedEndOfMonth: number;
  accounts: BankAccount[];
  month: MonthSummary;
  pendingCount: number;
  invoicesToPay: number;
  creditLines: CreditLine[];
  upcoming: Occurrence[];
}

// ------------------------------------------------------------------ recorrências

export type Frequency = 'SEMANAL' | 'MENSAL' | 'ANUAL';
export type OccurrenceStatus = 'REALIZADA' | 'PULADA' | 'ATRASADA' | 'PREVISTA';

export const OCCURRENCE_LABEL: Record<OccurrenceStatus, string> = {
  REALIZADA: 'Lançada', PULADA: 'Pulada', ATRASADA: 'Atrasada', PREVISTA: 'Prevista',
};

export interface Recurrence {
  id: string;
  kind: 'RECEITA' | 'DESPESA';
  description: string;
  amount: number;
  frequency: Frequency;
  intervalCount: number;
  dayOfMonth: number | null;
  startDate: string;
  endDate: string | null;
  walletId: string | null;
  cardId: string | null;
  credit: boolean;
  consumesLimit: boolean;
  paidWith: string;
  categoryId: string | null;
  categoryName: string | null;
  originalCurrency: string | null;
  originalAmount: number | null;
  notes: string | null;
  active: boolean;
  nextDate: string | null;
}

export interface RecurrenceRequest {
  kind: 'RECEITA' | 'DESPESA';
  description: string;
  amount: number;
  frequency: Frequency;
  intervalCount: number;
  dayOfMonth: number | null;
  startDate: string;
  endDate: string | null;
  walletId: string | null;
  cardId: string | null;
  credit: boolean | null;
  consumesLimit: boolean | null;
  categoryId: string | null;
  originalCurrency: string | null;
  originalAmount: number | null;
  notes: string | null;
  active: boolean;
}

export interface Occurrence {
  recurrenceId: string;
  description: string;
  kind: 'RECEITA' | 'DESPESA';
  date: string;
  expectedAmount: number;
  status: OccurrenceStatus;
  transactionId: string | null;
  actualAmount: number | null;
  paidWith: string;
  credit: boolean;
  candidates: number;
  originalCurrency: string | null;
  originalAmount: number | null;
}

export interface RecurrenceMonth {
  month: string;
  expectedExpense: number;
  realizedExpense: number;
  pendingExpense: number;
  expectedIncome: number;
  realizedIncome: number;
  pendingIncome: number;
  occurrences: Occurrence[];
  recurrences: Recurrence[];
}

// ------------------------------------------------------------------ administração

export interface AdminUser {
  id: string;
  username: string;
  role: Role;
  active: boolean;
  createdAt: string;
  lastLoginAt: string | null;
  you: boolean;
}

export interface NewInvite {
  id: string;
  code: string;
  expiresAt: string;
}

export type InviteStatus = 'PENDENTE' | 'USADO' | 'EXPIRADO' | 'REVOGADO';

export interface InviteInfo {
  id: string;
  createdAt: string;
  expiresAt: string;
  usedBy: string | null;
  usedAt: string | null;
  revokedAt: string | null;
  status: InviteStatus;
}

// ------------------------------------------------------------------ tokens de API, importações e anexos

export interface ApiToken {
  id: string;
  name: string;
  prefix: string;
  canWrite: boolean;
  createdAt: string;
  expiresAt: string;
  lastUsedAt: string | null;
  revokedAt: string | null;
  active: boolean;
  /** Só vem na criação. */
  token: string | null;
}

export interface ActivityEntry {
  id: string;
  at: string;
  source: 'WEB' | 'API';
  tokenName: string | null;
  action: string;
  entityId: string | null;
  details: string | null;
}

export const ACTIVITY_LABEL: Record<string, string> = {
  TOKEN_CRIADO: 'Token criado', TOKEN_REVOGADO: 'Token revogado', IMPORTACAO: 'Importação',
  IMPORTACAO_CONFIRMADA: 'Importação confirmada', IMPORTACAO_DESFEITA: 'Importação desfeita',
  LANCAMENTO_EDITADO: 'Lançamento editado', LANCAMENTO_CONFIRMADO: 'Lançamento confirmado',
  LANCAMENTO_ESTORNADO: 'Lançamento estornado', ANEXOS_ENVIADOS: 'Arquivos enviados',
  MCP_CONECTADO: 'Claude conectado',
};

export interface Attachment {
  id: string;
  name: string;
  mimeType: string;
  size: number;
  sha256: string;
  createdAt: string;
}

export type ImportMode = 'ANALISAR' | 'CONFIRMADO' | 'REMOVER';
export type ImportItemStatus = 'CRIADO' | 'PENDENTE' | 'DUPLICADO' | 'ERRO' | 'REMOVIDO' | 'ESTORNADO' | 'DESFEITO';

export const IMPORT_MODE_LABEL: Record<ImportMode, string> = {
  ANALISAR: 'Analisar', CONFIRMADO: 'Confirmado', REMOVER: 'Remover',
};
export const IMPORT_ITEM_LABEL: Record<ImportItemStatus, string> = {
  CRIADO: 'Lançado', PENDENTE: 'Pendente', DUPLICADO: 'Duplicado (ignorado)', ERRO: 'Erro', REMOVIDO: 'Cancelado',
  ESTORNADO: 'Estornado', DESFEITO: 'Desfeito',
};

export interface ImportItemResult {
  position: number;
  status: ImportItemStatus;
  transactionId: string | null;
  date: string | null;
  description: string | null;
  amount: number | null;
  message: string | null;
}

export interface ImportBatch {
  id: string;
  mode: ImportMode;
  note: string | null;
  status: 'PROCESSADO' | 'DESFEITO';
  createdAt: string;
  undoneAt: string | null;
  tokenName: string | null;
  replayed: boolean;
  created: number;
  pending: number;
  duplicates: number;
  errors: number;
  removed: number;
  items: ImportItemResult[];
  attachments: Attachment[];
}
