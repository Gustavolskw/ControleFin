import { Component, OnInit, inject, signal } from '@angular/core';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../core/auth.interceptor';
import { DateTimeBrPipe } from '../core/format';
import { AdminUser, InviteInfo, NewInvite } from '../core/models';

/**
 * Administração: convites e ativação de contas. O administrador não vê dados financeiros de ninguém
 * (o backend não tem nenhum endpoint que permita isso).
 */
@Component({
  selector: 'app-admin',
  imports: [DateTimeBrPipe],
  template: `
    <div class="page-head">
      <h1>Administração</h1>
      <p class="sub">Convites e contas. Os dados financeiros de cada usuário são privados e não aparecem aqui.</p>
    </div>
    @if (error()) { <div class="error" role="alert">{{ error() }}</div> }

    <div class="grid">
      <section class="card">
        <h2>Convites</h2>
        <p class="hint" style="margin: -6px 0 12px">Cada código vale para <strong>um</strong> cadastro e expira em 48 h.
          Ele aparece só agora: copie e envie para a pessoa.</p>
        @if (newInvite(); as inv) {
          <div class="invite">
            <span class="muted">Código de convite</span>
            <code>{{ inv.code }}</code>
            <span class="hint">Válido até {{ inv.expiresAt | dateTimeBr }}</span>
            <button class="btn ghost small" type="button" (click)="copy(inv.code)">{{ copied() ? 'Copiado!' : 'Copiar' }}</button>
          </div>
        }
        <button class="btn" type="button" (click)="createInvite()" [disabled]="busy()">Gerar convite</button>

        @if (invites().length) {
          <div class="table-wrap" style="margin-top: 14px">
            <table class="data">
              <thead><tr><th>Criado</th><th>Situação</th><th>Usado por</th><th></th></tr></thead>
              <tbody>
                @for (i of invites(); track i.id) {
                  <tr>
                    <td>{{ i.createdAt | dateTimeBr }}</td>
                    <td><span class="tag" [class.ok]="i.status === 'USADO'" [class.accent]="i.status === 'PENDENTE'">{{ i.status }}</span></td>
                    <td>{{ i.usedBy ?? '—' }}</td>
                    <td class="actions">
                      @if (i.status === 'PENDENTE') {
                        <button class="btn danger small" type="button" (click)="revoke(i)">Revogar</button>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>

      <section class="card">
        <h2>Usuários</h2>
        <div class="table-wrap">
          <table class="data">
            <thead><tr><th>Usuário</th><th>Último acesso</th><th>Situação</th><th></th></tr></thead>
            <tbody>
              @for (u of users(); track u.id) {
                <tr>
                  <td>{{ u.username }} @if (u.role === 'ADMIN') { <span class="tag accent">admin</span> }
                    @if (u.you) { <span class="muted">(você)</span> }</td>
                  <td>{{ u.lastLoginAt | dateTimeBr }}</td>
                  <td><span class="tag" [class.ok]="u.active" [class.danger]="!u.active">{{ u.active ? 'Ativo' : 'Desativado' }}</span></td>
                  <td class="actions">
                    @if (!u.you) {
                      <button class="btn small" [class.danger]="u.active" [class.ghost]="!u.active" type="button"
                              (click)="toggle(u)">{{ u.active ? 'Desativar' : 'Reativar' }}</button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <p class="hint">Desativar bloqueia o login e encerra as sessões abertas. Os dados da pessoa continuam guardados.</p>
      </section>
    </div>
  `,
  styles: `
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; align-items: start; }
    .invite { display: flex; flex-direction: column; gap: 4px; align-items: flex-start; padding: 12px; margin-bottom: 12px;
              border: 1px dashed var(--accent); border-radius: 8px; background: var(--accent-soft); }
    .invite code { font-size: 22px; font-weight: 700; letter-spacing: 2px; color: var(--accent); user-select: all; }
    @media (max-width: 900px) { .grid { grid-template-columns: 1fr; } }
  `,
})
export class AdminComponent implements OnInit {
  private api = inject(ApiService);

  users = signal<AdminUser[]>([]);
  invites = signal<InviteInfo[]>([]);
  newInvite = signal<NewInvite | null>(null);
  copied = signal(false);
  busy = signal(false);
  error = signal('');

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.api.adminUsers().subscribe({ next: u => this.users.set(u), error: e => this.error.set(errorMessage(e)) });
    this.api.invites().subscribe({ next: i => this.invites.set(i), error: e => this.error.set(errorMessage(e)) });
  }

  createInvite(): void {
    this.busy.set(true);
    this.api.createInvite().subscribe({
      next: inv => {
        this.newInvite.set(inv);
        this.copied.set(false);
        this.busy.set(false);
        this.load();
      },
      error: e => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }

  revoke(i: InviteInfo): void {
    if (!confirm('Revogar este convite? O código deixará de funcionar.')) return;
    this.api.revokeInvite(i.id).subscribe({ next: () => this.load(), error: e => this.error.set(errorMessage(e)) });
  }

  toggle(u: AdminUser): void {
    const msg = u.active ? `Desativar ${u.username}? A pessoa não conseguirá mais entrar.` : `Reativar ${u.username}?`;
    if (!confirm(msg)) return;
    this.api.setUserActive(u.id, !u.active).subscribe({ next: () => this.load(), error: e => this.error.set(errorMessage(e)) });
  }

  copy(code: string): void {
    navigator.clipboard?.writeText(code).then(() => this.copied.set(true)).catch(() => {});
  }
}
