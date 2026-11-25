import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../core/auth.interceptor';
import { DateBrPipe, DateTimeBrPipe } from '../core/format';
import { ACTIVITY_LABEL, ActivityEntry, ApiToken } from '../core/models';

/**
 * Acesso do Claude (MCP): tokens pessoais da API externa e registro de atividade.
 * O token completo aparece uma única vez, logo após criar.
 */
@Component({
  selector: 'app-api-access',
  imports: [FormsModule, DateBrPipe, DateTimeBrPipe],
  template: `
    <section class="card">
      <h2>Acesso do Claude (API externa)</h2>
      <p class="hint" style="margin: -6px 0 12px">Crie um token para o Claude lançar e consultar seus dados pela API externa.
        Ele não dá acesso a login, senha nem cadastro. Guarde-o como uma senha; revogue se perder o aparelho.</p>
      @if (error()) { <div class="error">{{ error() }}</div> }

      @if (created(); as t) {
        <div class="success token-box">
          <strong>Token criado: copie agora, ele não aparece de novo.</strong>
          <div class="token-row">
            <code>{{ t.token }}</code>
            <button class="btn small" type="button" (click)="copy(t.token!)">{{ copied() ? 'Copiado' : 'Copiar' }}</button>
          </div>
          <button class="btn ghost small" type="button" (click)="created.set(null)">Já guardei</button>
        </div>
      }

      <form class="new" (ngSubmit)="create()">
        <div class="field" style="flex: 2">
          <label for="tn">Nome</label>
          <input id="tn" name="tn" [(ngModel)]="name" maxlength="60" placeholder="Ex.: Claude no celular" required>
        </div>
        <div class="field">
          <label for="tv">Validade</label>
          <select id="tv" name="tv" [(ngModel)]="days">
            <option [ngValue]="30">30 dias</option>
            <option [ngValue]="90">90 dias</option>
            <option [ngValue]="180">180 dias</option>
            <option [ngValue]="365">1 ano</option>
          </select>
        </div>
        <div class="field">
          <label for="tp">Permissão</label>
          <select id="tp" name="tp" [(ngModel)]="canWrite">
            <option [ngValue]="true">Ler e lançar</option>
            <option [ngValue]="false">Só consultar</option>
          </select>
        </div>
        <button class="btn" type="submit" [disabled]="busy() || !name.trim()">Criar token</button>
      </form>

      <div class="table-wrap">
        <table class="data">
          <thead><tr><th>Nome</th><th class="wide">Permissão</th><th>Válido até</th><th class="wide">Último uso</th><th></th></tr></thead>
          <tbody>
            @for (t of tokens(); track t.id) {
              <tr [class.dim]="!t.active">
                <td>{{ t.name }} <code class="muted small">{{ t.prefix }}…</code>
                  @if (t.revokedAt) { <span class="tag">Revogado</span> } @else if (!t.active) { <span class="tag warn">Vencido</span> }</td>
                <td class="wide">{{ t.canWrite ? 'Ler e lançar' : 'Só consultar' }}</td>
                <td>{{ t.expiresAt | dateBr }}</td>
                <td class="wide">{{ t.lastUsedAt ? (t.lastUsedAt | dateTimeBr) : 'Nunca' }}</td>
                <td class="actions">
                  @if (t.active) { <button class="btn ghost small danger-text" type="button" (click)="revoke(t)">Revogar</button> }
                </td>
              </tr>
            } @empty {
              <tr><td colspan="5" class="empty">Nenhum token criado.</td></tr>
            }
          </tbody>
        </table>
      </div>
    </section>

    <section class="card">
      <h2>Atividade recente</h2>
      <div class="table-wrap">
        <table class="data">
          <thead><tr><th>Quando</th><th>O quê</th><th class="wide">Origem</th><th class="wide">Detalhes</th></tr></thead>
          <tbody>
            @for (a of activity(); track a.id) {
              <tr>
                <td>{{ a.at | dateTimeBr }}</td>
                <td>{{ label(a.action) }}<div class="meta muted">{{ a.details }}</div></td>
                <td class="wide">@if (a.source === 'API') { <span class="tag accent">Claude</span> {{ a.tokenName }} } @else { Site }</td>
                <td class="muted wide">{{ a.details }}</td>
              </tr>
            } @empty {
              <tr><td colspan="4" class="empty">Nenhuma atividade ainda.</td></tr>
            }
          </tbody>
        </table>
      </div>
    </section>
  `,
  styles: `
    :host { display: grid; gap: 16px; margin-top: 16px; }
    .new { display: flex; gap: 10px; align-items: flex-end; flex-wrap: wrap; margin-bottom: 12px; }
    .new .field { margin: 0; min-width: 140px; }
    .token-box { display: grid; gap: 8px; margin-bottom: 12px; }
    .token-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .token-row code { word-break: break-all; font-size: 13px; padding: 6px 8px; background: var(--surface); border-radius: 6px; flex: 1; }
    .small { font-size: 12px; }
    tr.dim td:not(.actions) { opacity: .6; }
    .danger-text { color: var(--danger); }
    .meta { display: none; font-size: 12px; }
    @media (max-width: 700px) { .wide { display: none; } .meta { display: block; } }
  `,
})
export class ApiAccessComponent implements OnInit {
  private api = inject(ApiService);

  tokens = signal<ApiToken[]>([]);
  activity = signal<ActivityEntry[]>([]);
  created = signal<ApiToken | null>(null);
  copied = signal(false);
  error = signal('');
  busy = signal(false);
  name = '';
  days = 90;
  canWrite = true;

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.api.apiTokens().subscribe({ next: t => this.tokens.set(t), error: e => this.error.set(errorMessage(e)) });
    this.api.activity().subscribe({ next: a => this.activity.set(a), error: e => this.error.set(errorMessage(e)) });
  }

  label(action: string): string {
    return ACTIVITY_LABEL[action] ?? action;
  }

  create(): void {
    this.busy.set(true);
    this.error.set('');
    this.api.createApiToken(this.name.trim(), this.days, this.canWrite).subscribe({
      next: t => { this.busy.set(false); this.created.set(t); this.copied.set(false); this.name = ''; this.load(); },
      error: e => { this.busy.set(false); this.error.set(errorMessage(e)); },
    });
  }

  copy(value: string): void {
    navigator.clipboard?.writeText(value).then(() => this.copied.set(true));
  }

  revoke(t: ApiToken): void {
    if (!confirm(`Revogar o token "${t.name}"? O Claude que usa este token perde o acesso na hora.`)) return;
    this.api.revokeApiToken(t.id).subscribe({ next: () => this.load(), error: e => this.error.set(errorMessage(e)) });
  }
}
