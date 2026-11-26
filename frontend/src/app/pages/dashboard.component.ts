import { Component, computed, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../core/auth.interceptor';
import { BrlPipe, DateBrPipe, currentMonth } from '../core/format';
import { Dashboard, INVOICE_STATUS_LABEL, OCCURRENCE_LABEL, accountTypeLabel } from '../core/models';
import { LimitMeterComponent } from '../shared/limit-meter.component';
import { MonthPickerComponent } from '../shared/month-picker.component';

/** Painel: saldo total de todas as contas, por conta/carteira e resumo do mês. */
@Component({
  selector: 'app-dashboard',
  imports: [RouterLink, BrlPipe, DateBrPipe, LimitMeterComponent, MonthPickerComponent],
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
          <div class="label">Disponível em contas</div>
          <div class="value">{{ d.total | brl }}</div>
          <div class="detail">PF {{ d.totalPf | brl }} · PJ {{ d.totalPj | brl }}</div>
        </div>
        @if (d.invested > 0) {
          <a class="kpi link" routerLink="/investimentos">
            <div class="label">Investido</div>
            <div class="value">{{ d.invested | brl }}</div>
            <div class="detail">Patrimônio total: <strong>{{ d.patrimony | brl }}</strong></div>
          </a>
        }
        <div class="kpi">
          <div class="label">Previsão no fim do mês</div>
          <div class="value">{{ d.projectedEndOfMonth | brl }}</div>
          <div class="detail">com lançamentos agendados e recorrências</div>
        </div>
        @if (d.creditLines.length > 0) {
          <div class="kpi">
            <div class="label">Faturas a pagar</div>
            <div class="value neg">{{ d.invoicesToPay | brl }}</div>
            <div class="detail">Saldo após faturas: <span [class.neg]="d.total - d.invoicesToPay < 0">{{ d.total - d.invoicesToPay | brl }}</span></div>
          </div>
        }
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

        <section class="side">
        @if (d.upcoming.length > 0) {
          <section class="card">
            <div class="section-head"><h2>Próximos compromissos</h2><a routerLink="/recorrencias" class="btn ghost small">Ver todos</a></div>
            <ul class="upcoming">
              @for (o of d.upcoming; track o.recurrenceId + o.date) {
                <li>
                  <span class="date">{{ o.date | dateBr }}</span>
                  <span class="desc">{{ o.description }}
                    @if (o.status === 'ATRASADA') { <span class="tag warn">{{ occLabel[o.status] }}</span> }
                    <span class="muted small">{{ o.paidWith }}</span></span>
                  <span class="val" [class.pos]="o.kind === 'RECEITA'">{{ o.kind === 'RECEITA' ? '+' : '−' }}{{ o.expectedAmount | brl }}</span>
                </li>
              }
            </ul>
          </section>
        }
        @if (d.creditLines.length > 0) {
          <section class="card">
            <div class="section-head"><h2>Cartões de crédito</h2><a routerLink="/cartoes" class="btn ghost small">Gerenciar</a></div>
            <ul class="lines">
              @for (l of d.creditLines; track l.id) {
                <li>
                  <a class="line-title" [routerLink]="['/cartoes', l.id, 'faturas']">
                    <strong>{{ l.name }}</strong>
                    @if (l.dueInvoice; as inv) {
                      <span class="tag" [class.danger]="inv.status === 'VENCIDA'" [class.warn]="inv.status === 'FECHADA'">
                        {{ statusLabel[inv.status] }} {{ inv.outstanding | brl }} · vence {{ inv.dueDate | dateBr }}</span>
                    } @else if (l.currentInvoice) {
                      <span class="muted small">Fatura atual {{ l.currentInvoice.total | brl }} · vence {{ l.currentInvoice.dueDate | dateBr }}</span>
                    }
                  </a>
                  <app-limit-meter [used]="l.used" [limit]="l.limit" [available]="l.available" />
                </li>
              }
            </ul>
          </section>
        }
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
    .side { display: grid; gap: 16px; }
    a.kpi.link { color: inherit; text-decoration: none; }
    a.kpi.link:hover { border-color: var(--accent); }
    .upcoming { list-style: none; margin: 0; padding: 0; display: grid; gap: 8px; }
    .upcoming li { display: grid; grid-template-columns: 78px 1fr auto; gap: 8px; align-items: baseline; font-size: 13px; }
    .upcoming .date { color: var(--muted); font-variant-numeric: tabular-nums; }
    .upcoming .desc { display: flex; flex-wrap: wrap; gap: 4px 6px; align-items: baseline; }
    .upcoming .val { font-weight: 600; font-variant-numeric: tabular-nums; }
    .lines { list-style: none; margin: 0; padding: 0; display: grid; gap: 14px; }
    .line-title { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap;
      margin-bottom: 6px; color: var(--text); text-decoration: none; }
    .line-title:hover strong { text-decoration: underline; }
    @media (max-width: 900px) { .grid { grid-template-columns: 1fr; } }
  `,
})
export class DashboardComponent {
  private api = inject(ApiService);

  month = signal(currentMonth());
  data = signal<Dashboard | null>(null);
  error = signal('');
  readonly typeLabel = accountTypeLabel;
  readonly statusLabel = INVOICE_STATUS_LABEL;
  readonly occLabel = OCCURRENCE_LABEL;

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
