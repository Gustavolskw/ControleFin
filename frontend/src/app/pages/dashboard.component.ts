import { Component, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../core/auth.interceptor';
import { BrlPipe, currentMonth } from '../core/format';
import { Dashboard, accountTypeLabel } from '../core/models';
import { MonthPickerComponent } from '../shared/month-picker.component';

/** Painel: saldo total de todas as contas, por conta/carteira e resumo do mês. */
@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, BrlPipe, MonthPickerComponent],
  template: `
    <div class="page-head">
      <h1>Painel</h1>
      <app-month-picker [(month)]="month" />
    </div>
    @if (error()) { <div class="error" role="alert">{{ error() }}</div> }

    @if (data(); as d) {
      @if (d.pendingCount > 0) {
        <div class="warning">{{ d.pendingCount }} lançamento(s) pendente(s) de revisão.
          <a routerLink="/lancamentos" [queryParams]="{ status: 'PENDENTE' }">Revisar</a></div>
      }

      <div class="kpis">
        <div class="kpi main">
          <div class="label">Saldo total (todas as contas)</div>
          <div class="value">{{ d.total | brl }}</div>
          <div class="detail">PF {{ d.totalPf | brl }} · PJ {{ d.totalPj | brl }}</div>
        </div>
        <div class="kpi">
          <div class="label">Previsão no fim do mês</div>
          <div class="value">{{ d.projectedEndOfMonth | brl }}</div>
          <div class="detail">com lançamentos já agendados</div>
        </div>
        <div class="kpi">
          <div class="label">Receitas no mês</div>
          <div class="value pos">{{ d.month.income | brl }}</div>
        </div>
        <div class="kpi">
          <div class="label">Despesas no mês</div>
          <div class="value neg">{{ d.month.expense | brl }}</div>
          <div class="detail">Resultado: <span [class.pos]="d.month.result >= 0" [class.neg]="d.month.result < 0">{{ d.month.result | brl }}</span></div>
        </div>
      </div>

      <div class="grid">
        <section>
          <div class="section-head"><h2>Contas</h2><a routerLink="/contas" class="btn ghost small">Gerenciar</a></div>
          @if (activeAccounts().length === 0) {
            <div class="card empty">Nenhuma conta cadastrada. <a routerLink="/contas">Cadastre a primeira</a>.</div>
          }
          <div class="accounts">
            @for (a of activeAccounts(); track a.id) {
              <div class="card account" [style.border-left-color]="a.color || 'var(--brand)'">
                <div class="acc-head">
                  <div>
                    <strong>{{ a.name }}</strong>
                    <div class="muted small">{{ typeLabel(a.type) }} · {{ a.holder }}</div>
                  </div>
                  <div class="acc-total" [class.neg]="a.balance < 0">{{ a.balance | brl }}</div>
                </div>
                @if (activeWallets(a).length > 1) {
                  <ul class="wallets">
                    @for (w of activeWallets(a); track w.id) {
                      <li><span>{{ w.name }}</span><span [class.neg]="w.balance < 0">{{ w.balance | brl }}</span></li>
                    }
                  </ul>
                }
              </div>
            }
          </div>
        </section>

        <section class="card">
          <h2>Despesas por categoria</h2>
          @if (d.month.expensesByCategory.length === 0) {
            <p class="muted">Sem despesas confirmadas neste mês.</p>
          } @else {
            <ul class="bars" role="list">
              @for (c of d.month.expensesByCategory; track c.name) {
                <li [title]="c.name + ': ' + (c.amount | brl) + ' (' + pct(c.amount, d.month.expense) + '%)'">
                  <div class="bar-label"><span>{{ c.name }}</span><span class="val">{{ c.amount | brl }}</span></div>
                  <div class="track"><div class="fill" [style.width.%]="share(c.amount)"></div></div>
                </li>
              }
            </ul>
          }
        </section>
      </div>
    } @else if (!error()) {
      <p class="muted">Carregando…</p>
    }
  `,
  styles: `
    .grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: 16px; align-items: start; }
    .section-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
    .section-head h2 { margin: 0; }
    .accounts { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 12px; }
    .account { border-left: 4px solid var(--brand); }
    .acc-head { display: flex; justify-content: space-between; gap: 8px; align-items: flex-start; }
    .acc-total { font-weight: 700; font-size: 16px; font-variant-numeric: tabular-nums; white-space: nowrap; }
    .small { font-size: 12px; }
    .wallets { list-style: none; padding: 8px 0 0; margin: 10px 0 0; border-top: 1px solid var(--border); }
    .wallets li { display: flex; justify-content: space-between; padding: 3px 0; color: var(--muted); font-variant-numeric: tabular-nums; }
    .bars { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
    .bar-label { display: flex; justify-content: space-between; gap: 8px; font-size: 13px; margin-bottom: 4px; }
    .bar-label .val { color: var(--text); font-weight: 600; font-variant-numeric: tabular-nums; }
    .track { height: 8px; background: var(--surface-2); border-radius: 4px; overflow: hidden; }
    .fill { height: 100%; background: var(--brand); border-radius: 4px; min-width: 2px; }
    .bars li:hover .fill { background: var(--brand-strong); }
    @media (max-width: 900px) { .grid { grid-template-columns: 1fr; } }
  `,
})
export class DashboardComponent {
  private api = inject(ApiService);

  month = signal(currentMonth());
  data = signal<Dashboard | null>(null);
  error = signal('');
  readonly typeLabel = accountTypeLabel;

  activeAccounts = computed(() => (this.data()?.accounts ?? []).filter(a => !a.archived));
  private maxExpense = computed(() => Math.max(0, ...(this.data()?.month.expensesByCategory ?? []).map(c => c.amount)));

  constructor() {
    effect(() => {
      const m = this.month();
      this.api.dashboard(m).subscribe({
        next: d => { this.data.set(d); this.error.set(''); },
        error: e => this.error.set(errorMessage(e)),
      });
    });
  }

  activeWallets(a: Dashboard['accounts'][number]) {
    return a.wallets.filter(w => !w.archived);
  }

  /** Largura da barra relativa à maior categoria (a maior ocupa a linha inteira). */
  share(amount: number): number {
    const max = this.maxExpense();
    return max > 0 ? (amount / max) * 100 : 0;
  }

  pct(amount: number, total: number): string {
    return total > 0 ? ((amount / total) * 100).toFixed(1).replace('.', ',') : '0';
  }
}
