import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { QuestionAnswer, Role } from './models';

export interface TokenResponse {
  token: string;
  username: string;
  role: Role;
  /** Primeiro acesso pendente: trocar a senha inicial e/ou cadastrar as perguntas de segurança. */
  setupRequired: boolean;
}

const TOKEN_KEY = 'cf.token';
const USER_KEY = 'cf.username';
const ROLE_KEY = 'cf.role';
const SETUP_KEY = 'cf.setup';

function read(key: string): string | null {
  try {
    return localStorage.getItem(key);
  } catch {
    return null;
  }
}

/**
 * Sessão do site (mesmo padrão do Chama&Essencia: JWT no localStorage, enviado pelo interceptor).
 * O papel (role) aqui serve só para montar o menu; quem garante o acesso é o backend.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private http = inject(HttpClient);
  private router = inject(Router);

  readonly token = signal<string | null>(read(TOKEN_KEY));
  readonly username = signal<string | null>(read(USER_KEY));
  readonly role = signal<Role | null>(read(ROLE_KEY) as Role | null);
  readonly setupRequired = signal<boolean>(read(SETUP_KEY) === '1');
  readonly isLoggedIn = computed(() => !!this.token());
  readonly isAdmin = computed(() => this.role() === 'ADMIN');

  login(username: string, password: string): Observable<TokenResponse> {
    return this.http.post<TokenResponse>('/api/auth/login', { username, password })
      .pipe(tap(r => this.store(r)));
  }

  register(username: string, password: string, securityQuestions: QuestionAnswer[],
           inviteCode: string): Observable<TokenResponse> {
    return this.http.post<TokenResponse>('/api/auth/register', { username, password, securityQuestions, inviteCode })
      .pipe(tap(r => this.store(r)));
  }

  recoveryQuestions(username: string): Observable<{ questions: string[] }> {
    return this.http.get<{ questions: string[] }>(`/api/auth/recovery/${encodeURIComponent(username)}`);
  }

  resetPassword(username: string, answers: string[], newPassword: string): Observable<void> {
    return this.http.post<void>('/api/auth/recovery/reset', { username, answers, newPassword });
  }

  logout(): void {
    this.token.set(null);
    this.username.set(null);
    this.role.set(null);
    this.setupRequired.set(false);
    try {
      [TOKEN_KEY, USER_KEY, ROLE_KEY, SETUP_KEY].forEach(k => localStorage.removeItem(k));
    } catch { /* armazenamento indisponível */ }
    this.router.navigate(['/login']);
  }

  /** Atualiza a pendência de primeiro acesso (após trocar a senha / cadastrar as perguntas). */
  setSetupRequired(required: boolean): void {
    this.setupRequired.set(required);
    try {
      if (required) localStorage.setItem(SETUP_KEY, '1'); else localStorage.removeItem(SETUP_KEY);
    } catch { /* armazenamento indisponível */ }
  }

  /** Guarda a sessão (login, cadastro ou token novo após trocar a senha). */
  store(r: TokenResponse): void {
    this.token.set(r.token);
    this.username.set(r.username);
    this.role.set(r.role);
    this.setSetupRequired(r.setupRequired);
    try {
      localStorage.setItem(TOKEN_KEY, r.token);
      localStorage.setItem(USER_KEY, r.username);
      localStorage.setItem(ROLE_KEY, r.role);
    } catch { /* sessão fica só em memória */ }
  }
}
