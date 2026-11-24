import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { TokenResponse } from './auth.service';
import { Account, AdminUser, InviteInfo, NewInvite, QuestionAnswer } from './models';

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
