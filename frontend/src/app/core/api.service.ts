import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { TokenResponse } from './auth.service';
import {
  Account, AccountRequest, ActivityEntry, AdminUser, ApiToken, Attachment, ImportBatch, BankAccount, Card, CardRequest, Category, CategoryRequest, CreditLine,
  CreditLineRequest, Dashboard, InvestmentDetail, InvestmentRequest, Investment, MovementRequest, Portfolio,
  InviteInfo, InvoiceDetail, InvoiceSummary, NewInvite, QuestionAnswer, Recurrence,
  RecurrenceMonth, RecurrenceRequest, Transaction, TransactionFilter, TransactionKind, TransactionRequest,
  WalletRequest,
} from './models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private http = inject(HttpClient);

  // --- perfil
  account(): Observable<Account> {
    return this.http.get<Account>('/api/account');
  }

  /** Devolve um token novo: trocar a senha encerra as outras sessões. */
  changePassword(currentPassword: string, newPassword: string): Observable<TokenResponse> {
    return this.http.put<TokenResponse>('/api/account/password', { currentPassword, newPassword });
  }

  changeQuestions(currentPassword: string, securityQuestions: QuestionAnswer[]): Observable<void> {
    return this.http.put<void>('/api/account/security-questions', { currentPassword, securityQuestions });
  }

  // --- painel
  dashboard(month?: string): Observable<Dashboard> {
    return this.http.get<Dashboard>('/api/dashboard', { params: month ? { month } : {} });
  }

  // --- contas e carteiras
  accounts(): Observable<BankAccount[]> {
    return this.http.get<BankAccount[]>('/api/accounts');
  }

  createAccount(req: AccountRequest): Observable<BankAccount> {
    return this.http.post<BankAccount>('/api/accounts', req);
  }

  updateAccount(id: string, req: AccountRequest): Observable<BankAccount> {
    return this.http.put<BankAccount>(`/api/accounts/${id}`, req);
  }

  deleteAccount(id: string): Observable<void> {
    return this.http.delete<void>(`/api/accounts/${id}`);
  }

  addWallet(accountId: string, req: WalletRequest): Observable<BankAccount> {
    return this.http.post<BankAccount>(`/api/accounts/${accountId}/wallets`, req);
  }

  updateWallet(id: string, req: WalletRequest & { archived: boolean }): Observable<BankAccount> {
    return this.http.put<BankAccount>(`/api/wallets/${id}`, req);
  }

  deleteWallet(id: string): Observable<void> {
    return this.http.delete<void>(`/api/wallets/${id}`);
  }

  adjustWallet(id: string, balance: number, date: string | null, description: string | null): Observable<BankAccount> {
    return this.http.post<BankAccount>(`/api/wallets/${id}/adjust`, { balance, date, description });
  }

  // --- categorias
  categories(): Observable<Category[]> {
    return this.http.get<Category[]>('/api/categories');
  }

  createCategory(req: CategoryRequest): Observable<Category> {
    return this.http.post<Category>('/api/categories', req);
  }

  updateCategory(id: string, req: CategoryRequest): Observable<Category> {
    return this.http.put<Category>(`/api/categories/${id}`, req);
  }

  deleteCategory(id: string): Observable<void> {
    return this.http.delete<void>(`/api/categories/${id}`);
  }

  // --- lançamentos
  transactions(f: TransactionFilter): Observable<Transaction[]> {
    const params: Record<string, string> = {};
    for (const [k, v] of Object.entries(f)) if (v) params[k] = String(v);
    return this.http.get<Transaction[]>('/api/transactions', { params });
  }

  createTransaction(req: TransactionRequest): Observable<Transaction> {
    return this.http.post<Transaction>('/api/transactions', req);
  }

  updateTransaction(id: string, req: TransactionRequest): Observable<Transaction> {
    return this.http.put<Transaction>(`/api/transactions/${id}`, req);
  }

  deleteTransaction(id: string): Observable<void> {
    return this.http.delete<void>(`/api/transactions/${id}`);
  }

  /** Possíveis duplicados (mesmo tipo e valor, até 3 dias, mesma carteira/cartão). */
  similarTransactions(kind: TransactionKind, date: string, amount: number, walletId: string | null,
                      cardId: string | null, excludeId: string | null = null): Observable<Transaction[]> {
    const params: Record<string, string> = { kind, date, amount: String(amount) };
    if (walletId) params['walletId'] = walletId;
    if (cardId) params['cardId'] = cardId;
    if (excludeId) params['excludeId'] = excludeId;
    return this.http.get<Transaction[]>('/api/transactions/similar', { params });
  }

  // --- recorrências
  recurrences(): Observable<Recurrence[]> {
    return this.http.get<Recurrence[]>('/api/recurrences');
  }

  recurrenceMonth(month: string): Observable<RecurrenceMonth> {
    return this.http.get<RecurrenceMonth>('/api/recurrences/month', { params: { month } });
  }

  createRecurrence(req: RecurrenceRequest): Observable<Recurrence> {
    return this.http.post<Recurrence>('/api/recurrences', req);
  }

  updateRecurrence(id: string, req: RecurrenceRequest): Observable<Recurrence> {
    return this.http.put<Recurrence>(`/api/recurrences/${id}`, req);
  }

  deleteRecurrence(id: string): Observable<void> {
    return this.http.delete<void>(`/api/recurrences/${id}`);
  }

  skipOccurrence(id: string, date: string): Observable<void> {
    return this.http.post<void>(`/api/recurrences/${id}/skips/${date}`, null);
  }

  unskipOccurrence(id: string, date: string): Observable<void> {
    return this.http.delete<void>(`/api/recurrences/${id}/skips/${date}`);
  }

  occurrenceCandidates(id: string, date: string): Observable<Transaction[]> {
    return this.http.get<Transaction[]>(`/api/recurrences/${id}/occurrences/${date}/candidates`);
  }

  linkRecurrence(transactionId: string, recurrenceId: string | null, recurrenceDate: string | null): Observable<Transaction> {
    return this.http.put<Transaction>(`/api/transactions/${transactionId}/recurrence`, { recurrenceId, recurrenceDate });
  }

  /** Estorna/cancela uma compra (nunca apaga). */
  reverseTransaction(id: string, date: string | null = null): Observable<Transaction> {
    return this.http.post<Transaction>(`/api/transactions/${id}/reverse`, { date });
  }

  // --- cartões, limites e faturas
  creditLines(): Observable<CreditLine[]> {
    return this.http.get<CreditLine[]>('/api/credit-lines');
  }

  createCreditLine(req: CreditLineRequest): Observable<CreditLine> {
    return this.http.post<CreditLine>('/api/credit-lines', req);
  }

  updateCreditLine(id: string, req: CreditLineRequest): Observable<CreditLine> {
    return this.http.put<CreditLine>(`/api/credit-lines/${id}`, req);
  }

  deleteCreditLine(id: string): Observable<void> {
    return this.http.delete<void>(`/api/credit-lines/${id}`);
  }

  invoices(creditLineId: string): Observable<InvoiceSummary[]> {
    return this.http.get<InvoiceSummary[]>(`/api/credit-lines/${creditLineId}/invoices`);
  }

  invoice(id: string): Observable<InvoiceDetail> {
    return this.http.get<InvoiceDetail>(`/api/invoices/${id}`);
  }

  cards(): Observable<Card[]> {
    return this.http.get<Card[]>('/api/cards');
  }

  createCard(req: CardRequest): Observable<Card> {
    return this.http.post<Card>('/api/cards', req);
  }

  updateCard(id: string, req: CardRequest): Observable<Card> {
    return this.http.put<Card>(`/api/cards/${id}`, req);
  }

  deleteCard(id: string): Observable<void> {
    return this.http.delete<void>(`/api/cards/${id}`);
  }

  // --- tokens de API (Claude) e atividade
  apiTokens(): Observable<ApiToken[]> {
    return this.http.get<ApiToken[]>('/api/account/tokens');
  }

  createApiToken(name: string, days: number, canWrite: boolean): Observable<ApiToken> {
    return this.http.post<ApiToken>('/api/account/tokens', { name, days, canWrite });
  }

  revokeApiToken(id: string): Observable<void> {
    return this.http.delete<void>(`/api/account/tokens/${id}`);
  }

  activity(): Observable<ActivityEntry[]> {
    return this.http.get<ActivityEntry[]>('/api/account/activity');
  }

  // --- importações do Claude
  imports(): Observable<ImportBatch[]> {
    return this.http.get<ImportBatch[]>('/api/imports');
  }

  importBatch(id: string): Observable<ImportBatch> {
    return this.http.get<ImportBatch>(`/api/imports/${id}`);
  }

  confirmImport(id: string): Observable<ImportBatch> {
    return this.http.post<ImportBatch>(`/api/imports/${id}/confirm`, null);
  }

  undoImport(id: string): Observable<ImportBatch> {
    return this.http.post<ImportBatch>(`/api/imports/${id}/undo`, null);
  }

  // --- anexos
  transactionAttachments(id: string): Observable<Attachment[]> {
    return this.http.get<Attachment[]>(`/api/transactions/${id}/attachments`);
  }

  uploadAttachment(transactionId: string, file: File): Observable<Attachment> {
    const form = new FormData();
    form.append('file', file);
    return this.http.post<Attachment>(`/api/transactions/${transactionId}/attachments`, form);
  }

  /** Baixa o anexo (com o token no header) e entrega ao navegador como arquivo. */
  downloadAttachment(a: Attachment): void {
    this.http.get(`/api/attachments/${a.id}`, { responseType: 'blob' }).subscribe(blob => {
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = a.name;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 10000);
    });
  }

  // --- administração (somente admin)
  adminUsers(): Observable<AdminUser[]> {
    return this.http.get<AdminUser[]>('/api/admin/users');
  }

  setUserActive(id: string, active: boolean): Observable<void> {
    return this.http.put<void>(`/api/admin/users/${id}/active`, { active });
  }

  invites(): Observable<InviteInfo[]> {
    return this.http.get<InviteInfo[]>('/api/admin/invites');
  }

  createInvite(): Observable<NewInvite> {
    return this.http.post<NewInvite>('/api/admin/invites', null);
  }

  revokeInvite(id: string): Observable<void> {
    return this.http.delete<void>(`/api/admin/invites/${id}`);
  }

  // --- investimentos
  portfolio(): Observable<Portfolio> {
    return this.http.get<Portfolio>('/api/investments');
  }

  investment(id: string): Observable<InvestmentDetail> {
    return this.http.get<InvestmentDetail>(`/api/investments/${id}`);
  }

  createInvestment(req: InvestmentRequest): Observable<Investment> {
    return this.http.post<Investment>('/api/investments', req);
  }

  updateInvestment(id: string, req: InvestmentRequest): Observable<Investment> {
    return this.http.put<Investment>(`/api/investments/${id}`, req);
  }

  deleteInvestment(id: string): Observable<void> {
    return this.http.delete<void>(`/api/investments/${id}`);
  }

  addMovement(investmentId: string, req: MovementRequest): Observable<InvestmentDetail> {
    return this.http.post<InvestmentDetail>(`/api/investments/${investmentId}/movements`, req);
  }

  updateMovement(investmentId: string, movementId: string, req: MovementRequest): Observable<InvestmentDetail> {
    return this.http.put<InvestmentDetail>(`/api/investments/${investmentId}/movements/${movementId}`, req);
  }

  deleteMovement(investmentId: string, movementId: string): Observable<InvestmentDetail> {
    return this.http.delete<InvestmentDetail>(`/api/investments/${investmentId}/movements/${movementId}`);
  }
}
