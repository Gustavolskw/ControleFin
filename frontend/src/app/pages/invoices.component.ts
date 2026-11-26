import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../core/auth.interceptor';
import { BrlPipe, DateBrPipe, MonthBrPipe, isoDate } from '../core/format';
import {
  BankAccount, CreditLine, INVOICE_STATUS_LABEL, InvoiceDetail, InvoiceSummary, STATUS_LABEL, Transaction,
} from '../core/models';

interface PayForm {
  walletId: string;
  amount: number;
  date: string;
}

interface WalletOption { id: string; label: string; }

/** Faturas de um cartão de crédito: itens, totais, pagamento (parcial ou total) e estorno de compras. */
@Component({
  selector: 'app-invoices',
  imports: [FormsModule, RouterLink, BrlPipe, DateBrPipe, MonthBrPipe],
  template: `
    <div class="page-head">
      <a routerLink="/cartoes" class="btn ghost small">‹ Cartões</a>
      <h1>{{ line()?.name ?? 'Faturas' }}</h1>
      @if (list().length > 0) {
        <div class="nav">
          <button type="button" class="icon-btn" (click)="go(-1)" [disabled]="index() <= 0" aria-label="Fatura anterior">‹</button>
          <select [ngModel]="selectedId()" (ngModelChange)="select($event)" aria-label="Fatura">
            @for (i of list(); track i.id) { <option [value]="i.id">{{ i.reference | monthBr }} · {{ statusLabel[i.status] }}</option> }
          </select>
          <button type="button" class="icon-btn" (click)="go(1)" [disabled]="index() >= list().length - 1" aria-label="Próxima fatura">›</button>
        </div>
      }
    </div>
    @if (error()) { <div class="error" role="alert">{{ error() }}</div> }

    @if (detail(); as d) {
      <div class="kpis">
        <div class="kpi main">
          <div class="label">Fatura de {{ d.invoice.reference | monthBr }}</div>
          <div class="value">{{ d.invoice.total | brl }}</div>
          <div class="detail">Fecha {{ d.invoice.closingDate | dateBr }} · vence {{ d.invoice.dueDate | dateBr }}</div>
        </div>
        <div class="kpi">
          <div class="label">Pago</div>
          <div class="value">{{ d.invoice.paid | brl }}</div>
          @if (d.invoice.carriedCredit > 0) { <div class="detail">+ {{ d.invoice.carriedCredit | brl }} de crédito da fatura anterior</div> }
        </div>
        <div class="kpi">
          <div class="label">{{ d.invoice.outstanding < 0 ? 'Pago a mais' : 'A pagar' }}</div>
          <div class="value" [class.neg]="d.invoice.status === 'VENCIDA'">{{ abs(d.invoice.outstanding) | brl }}</div>
          <div class="detail">
            @if (d.invoice.outstanding < 0) { <span>vai como crédito para a próxima fatura · </span> }
            <span class="tag" [class.ok]="d.invoice.status === 'PAGA'" [class.danger]="d.invoice.status === 'VENCIDA'"
                  [class.warn]="d.invoice.status === 'FECHADA'" [class.accent]="d.invoice.status === 'ABERTA'">{{ statusLabel[d.invoice.status] }}</span>
          </div>
        </div>
      </div>

      <div class="actions-bar">
        @if (d.invoice.nonConsuming > 0) {
          <span class="muted small">{{ d.invoice.nonConsuming | brl }} desta fatura não reservam limite (ex.: assinaturas).</span>
        }
        <span class="spacer"></span>
        @if (firstCard(); as cardId) {
          <a class="btn ghost" routerLink="/lancamentos" [queryParams]="{ novo: cardId }">Lançar compra</a>
        }
        <button class="btn" type="button" (click)="openPay(d.invoice)" [disabled]="d.invoice.total <= 0 && d.invoice.outstanding <= 0">Pagar fatura</button>
      </div>

      <div class="table-wrap">
        <table class="data">
          <thead><tr><th>Data</th><th>Descrição</th><th class="wide">Cartão</th><th class="wide">Categoria</th><th class="r">Valor</th><th class="wide"></th></tr></thead>
          <tbody>
            @for (t of d.items; track t.id) {
              <tr [class.dim]="t.status !== 'CONFIRMADO'" [class.payment]="t.kind === 'PAGAMENTO_FATURA'">
                <td>{{ t.date | dateBr }}</td>
                <td>
                  {{ t.description }}
                  @if (t.installmentCount) { <span class="tag">{{ t.installmentNumber }}/{{ t.installmentCount }}</span> }
                  @if (t.kind === 'PAGAMENTO_FATURA') { <span class="tag ok">Pagamento</span> }
                  @if (t.kind === 'ESTORNO') { <span class="tag ok">Estorno</span> }
                  @if (t.status !== 'CONFIRMADO') { <span class="tag" [class.warn]="t.status === 'PENDENTE'">{{ txStatus[t.status] }}</span> }
                  @if (!t.consumesLimit && t.kind === 'DESPESA') { <span class="tag" title="Entra na fatura, mas não reserva limite">Não usa limite</span> }
                  @if (t.originalCurrency) { <span class="tag" [title]="'Cotação ' + t.exchangeRate + (t.iof ? ' · IOF ' + (t.iof | brl) : '')">{{ t.originalCurrency }} {{ t.originalAmount }}</span> }
                  @if (t.origin === 'API') { <span class="tag accent">Claude</span> }
                  <div class="meta muted">{{ t.kind === 'PAGAMENTO_FATURA' ? (t.accountName + ' · ' + t.walletName) : t.cardName }}@if (t.categoryName) { · {{ t.categoryName }} }</div>
                </td>
                <td class="muted wide">{{ t.kind === 'PAGAMENTO_FATURA' ? (t.accountName + ' · ' + t.walletName) : t.cardName }}</td>
                <td class="muted wide">{{ t.categoryName ?? '—' }}</td>
                <td class="money-cell" [class.pos]="t.kind !== 'DESPESA'">{{ t.kind === 'DESPESA' ? '' : '−' }}{{ t.amount | brl }}</td>
                <td class="actions wide">
                  @if (t.kind === 'DESPESA' && t.status !== 'CANCELADO') {
                    <button class="btn ghost small" type="button" (click)="reverse(t)" title="Cancela parcelas que não fecharam e devolve o que já foi cobrado">Estornar</button>
                  }
                </td>
              </tr>
            } @empty {
              <tr><td colspan="6" class="empty">Nenhuma compra nesta fatura.</td></tr>
            }
          </tbody>
        </table>
      </div>
    } @else if (!error()) {
      <p class="muted">Carregando…</p>
    }

    @if (payForm(); as f) {
      <div class="backdrop" (click)="payForm.set(null)">
        <form class="card modal" (click)="$event.stopPropagation()" (ngSubmit)="pay(f)">
          <h2>Pagar fatura</h2>
          <p class="hint" style="margin-top: -8px">Sai da carteira escolhida e abate a fatura. Não conta como despesa
            (as compras já contaram). Pode pagar em partes.</p>
          @if (formError()) { <div class="error">{{ formError() }}</div> }
          <div class="field">
            <label for="pw">Pagar com</label>
            <select id="pw" name="pw" [(ngModel)]="f.walletId" required>
              <option value="" disabled>Escolha…</option>
              @for (w of walletOptions(); track w.id) { <option [value]="w.id">{{ w.label }}</option> }
            </select>
          </div>
          <div class="row">
            <div class="field">
              <label for="pa">Valor</label>
              <span class="money"><input id="pa" name="pa" type="number" step="0.01" min="0.01" [(ngModel)]="f.amount" required></span>
            </div>
            <div class="field">
              <label for="pd">Data</label>
              <input id="pd" name="pd" type="date" [(ngModel)]="f.date" required>
            </div>
          </div>
          <div class="form-actions">
            <button class="btn ghost" type="button" (click)="payForm.set(null)">Cancelar</button>
            <button class="btn" type="submit" [disabled]="busy()">Pagar</button>
          </div>
        </form>
      </div>
    }
  `,
  styles: `
    .page-head h1 { flex: 0 1 auto; }
    .nav { display: inline-flex; align-items: center; gap: 6px; }
    .nav select { min-width: 200px; }
    .actions-bar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 12px; }
    .small { font-size: 12px; }
    tr.dim td:not(.actions) { opacity: .65; }
    tr.payment td { background: color-mix(in srgb, var(--ok-soft) 50%, transparent); }
    td .tag { margin-left: 4px; }
    .meta { display: none; font-size: 12px; margin-top: 2px; }
    @media (max-width: 700px) {
      th.wide, td.wide { display: none; }
      .meta { display: block; }
    }
  `,
})
export class InvoicesComponent implements OnInit {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  readonly statusLabel = INVOICE_STATUS_LABEL;
  readonly txStatus = STATUS_LABEL;

  lineId = '';
  line = signal<CreditLine | null>(null);
  list = signal<InvoiceSummary[]>([]);
  selectedId = signal('');
  detail = signal<InvoiceDetail | null>(null);
  accounts = signal<BankAccount[]>([]);
  error = signal('');
  formError = signal('');
  busy = signal(false);
  payForm = signal<PayForm | null>(null);

  index = computed(() => this.list().findIndex(i => i.id === this.selectedId()));
  firstCard = computed(() => this.line()?.cards.find(c => !c.archived)?.id ?? null);
  walletOptions = computed<WalletOption[]>(() => this.accounts().filter(a => !a.archived).flatMap(a =>
    a.wallets.filter(w => !w.archived).map(w => ({ id: w.id, label: a.wallets.length > 1 ? `${a.name} · ${w.name}` : a.name }))));

  ngOnInit(): void {
    this.lineId = this.route.snapshot.paramMap.get('id') ?? '';
    this.api.accounts().subscribe({ next: a => this.accounts.set(a), error: e => this.error.set(errorMessage(e)) });
    this.api.creditLines().subscribe({
      next: lines => this.line.set(lines.find(l => l.id === this.lineId) ?? null),
      error: e => this.error.set(errorMessage(e)),
    });
    this.loadList(this.route.snapshot.queryParamMap.get('fatura'));
  }

  private loadList(wanted: string | null): void {
    this.api.invoices(this.lineId).subscribe({
      next: list => {
        this.list.set(list);
        const pick = list.find(i => i.id === wanted)
          ?? list.find(i => i.status === 'VENCIDA' || i.status === 'FECHADA')
          ?? list.find(i => i.status === 'ABERTA')
          ?? list[list.length - 1];
        if (pick) this.select(pick.id);
      },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  select(id: string): void {
    this.selectedId.set(id);
    this.router.navigate([], { queryParams: { fatura: id }, replaceUrl: true });
    this.api.invoice(id).subscribe({
      next: d => { this.detail.set(d); this.error.set(''); },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  go(delta: number): void {
    const next = this.list()[this.index() + delta];
    if (next) this.select(next.id);
  }

  private reload(): void {
    this.loadList(this.selectedId());
    this.api.creditLines().subscribe({ next: lines => this.line.set(lines.find(l => l.id === this.lineId) ?? null) });
  }

  abs(n: number): number {
    return Math.abs(n);
  }

  openPay(inv: InvoiceSummary): void {
    this.formError.set('');
    const fallback = this.walletOptions()[0]?.id ?? '';
    this.payForm.set({ walletId: this.line()?.paymentWalletId ?? fallback, amount: Math.max(0, inv.outstanding), date: isoDate() });
  }

  pay(f: PayForm): void {
    const amount = Number(f.amount);
    if (!f.walletId) return this.formError.set('Escolha de onde sai o pagamento.');
    if (!(amount > 0)) return this.formError.set('Informe um valor maior que zero.');
    const inv = this.detail()?.invoice;
    if (!inv) return;
    this.busy.set(true);
    this.api.createTransaction({ kind: 'PAGAMENTO_FATURA', status: 'CONFIRMADO', date: f.date,
      description: `Pagamento fatura ${inv.creditLineName} ${inv.reference.slice(5)}/${inv.reference.slice(0, 4)}`,
      amount, walletId: f.walletId, targetWalletId: null, categoryId: null, notes: null, invoiceId: inv.id,
    }).subscribe({
      next: () => { this.busy.set(false); this.payForm.set(null); this.reload(); },
      error: e => { this.busy.set(false); this.formError.set(errorMessage(e)); },
    });
  }

  reverse(t: Transaction): void {
    const what = t.installmentCount
      ? `Estornar a compra "${t.description}" (${t.installmentCount}x)? Parcelas de faturas que ainda não fecharam são canceladas e o que já foi cobrado volta como crédito na fatura aberta.`
      : `Estornar a compra "${t.description}"? Se a fatura ainda não fechou, a compra é cancelada; senão o valor volta como crédito na fatura aberta.`;
    if (!confirm(what)) return;
    this.api.reverseTransaction(t.id).subscribe({ next: () => this.reload(), error: e => this.error.set(errorMessage(e)) });
  }
}
