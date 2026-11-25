import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { TokenResponse } from './auth.service';
import {
  Account, AccountRequest, AdminUser, BankAccount, Card, CardRequest, Category, CategoryRequest, CreditLine,
  CreditLineRequest, Dashboard, InviteInfo, InvoiceDetail, InvoiceSummary, NewInvite, QuestionAnswer, Transaction,
  TransactionFilter, TransactionRequest, WalletRequest,
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
}
