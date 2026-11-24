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

export type TransactionKind = 'RECEITA' | 'DESPESA' | 'TRANSFERENCIA' | 'AJUSTE';
export type TransactionStatus = 'CONFIRMADO' | 'PENDENTE' | 'CANCELADO';

export const KIND_LABEL: Record<TransactionKind, string> = {
  RECEITA: 'Receita', DESPESA: 'Despesa', TRANSFERENCIA: 'Transferência', AJUSTE: 'Ajuste',
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
  walletId: string;
  walletName: string;
  accountId: string;
  accountName: string;
  targetWalletId: string | null;
  targetWalletName: string | null;
  targetAccountName: string | null;
  categoryId: string | null;
  categoryName: string | null;
  notes: string | null;
  origin: 'WEB' | 'API';
  createdAt: string;
  updatedAt: string;
}

export interface TransactionRequest {
  kind: TransactionKind;
  status: TransactionStatus;
  date: string;
  description: string;
  amount: number;
  walletId: string;
  targetWalletId: string | null;
  categoryId: string | null;
  notes: string | null;
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
