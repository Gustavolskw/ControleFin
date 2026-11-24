import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../core/auth.interceptor';
import { BrlPipe, DateBrPipe, currentMonth, isoDate, monthRange } from '../core/format';
import {
  BankAccount, Category, KIND_LABEL, STATUS_LABEL, Transaction, TransactionKind, TransactionRequest,
  TransactionStatus,
} from '../core/models';
import { MonthPickerComponent } from '../shared/month-picker.component';

interface TxForm extends TransactionRequest {
  id: string | null;
}

interface WalletOption { id: string; label: string; }
interface WalletGroup { account: string; wallets: WalletOption[]; }
interface CategoryGroup { parent: Category; children: Category[]; }

/** Lançamentos: receitas, despesas, transferências entre carteiras e ajustes. */
@Component({
  selector: 'app-transactions',
  imports: [FormsModule, BrlPipe, DateBrPipe, MonthPickerComponent],
  template: `
    <div class="page-head">
      <h1>Lançamentos</h1>
      <app-month-picker [month]="month()" (monthChange)="setMonth($event)" />
      <button class="btn" type="button" (click)="newTx('DESPESA')">Novo lançamento</button>
    </div>
    @if (error()) { <div class="error" role="alert">{{ error() }}</div> }

    <div class="filters">
      <div class="field">
        <label for="fa">Conta</label>
        <select id="fa" [(ngModel)]="accountId" (ngModelChange)="load()">
          <option value="">Todas</option>
          @for (a of accounts(); track a.id) { <option [value]="a.id">{{ a.name }}</option> }
        </select>
      </div>
      <div class="field">
        <label for="fk">Tipo</label>
        <select id="fk" [(ngModel)]="kind" (ngModelChange)="load()">
          <option value="">Todos</option>
          @for (k of kinds; track k) { <option [value]="k">{{ kindLabel[k] }}</option> }
        </select>
      </div>
      <div class="field">
        <label for="fs">Situação</label>
        <select id="fs" [(ngModel)]="status" (ngModelChange)="load()">
          <option value="">Todas</option>
          @for (s of statuses; track s) { <option [value]="s">{{ statusLabel[s] }}</option> }
        </select>
      </div>
      <div class="field">
        <label for="fc">Categoria</label>
        <select id="fc" [(ngModel)]="categoryId" (ngModelChange)="load()">
          <option value="">Todas</option>
          @for (g of categoryGroups('DESPESA').concat(categoryGroups('RECEITA')); track g.parent.id) {
            <option [value]="g.parent.id">{{ g.parent.name }}</option>
            @for (c of g.children; track c.id) { <option [value]="c.id">&nbsp;&nbsp;› {{ c.name }}</option> }
          }
        </select>
      </div>
      <div class="field" style="flex: 2">
        <label for="fq">Buscar</label>
        <input id="fq" type="search" [(ngModel)]="q" (keyup.enter)="load()" (search)="load()" placeholder="Descrição ou observação">
      </div>
    </div>

    <div class="kpis small">
      <div class="kpi"><div class="label">Receitas (confirmadas)</div><div class="value pos">{{ totals().income | brl }}</div></div>
      <div class="kpi"><div class="label">Despesas (confirmadas)</div><div class="value neg">{{ totals().expense | brl }}</div></div>
      <div class="kpi"><div class="label">Resultado</div><div class="value" [class.pos]="totals().income - totals().expense >= 0" [class.neg]="totals().income - totals().expense < 0">{{ totals().income - totals().expense | brl }}</div></div>
    </div>

    <div class="table-wrap">
      <table class="data">
        <thead><tr><th>Data</th><th>Descrição</th><th class="wide">Conta / carteira</th><th class="wide">Categoria</th><th class="r">Valor</th><th class="wide"></th></tr></thead>
        <tbody>
          @for (t of items(); track t.id) {
            <tr class="clickable" [class.dim]="t.status !== 'CONFIRMADO'" (click)="editTx(t)">
              <td>{{ t.date | dateBr }}</td>
              <td>
                {{ t.description }}
                @if (t.status !== 'CONFIRMADO') { <span class="tag" [class.warn]="t.status === 'PENDENTE'">{{ statusLabel[t.status] }}</span> }
                @if (t.kind === 'TRANSFERENCIA' || t.kind === 'AJUSTE') { <span class="tag">{{ kindLabel[t.kind] }}</span> }
                @if (t.origin === 'API') { <span class="tag accent" title="Enviado pelo Claude (API externa)">Claude</span> }
                <div class="meta muted">{{ t.accountName }} · {{ t.walletName }}@if (t.categoryName) { · {{ t.categoryName }} }</div>
              </td>
              <td class="muted wide">
                {{ t.accountName }} · {{ t.walletName }}
                @if (t.kind === 'TRANSFERENCIA') { → {{ t.targetAccountName }} · {{ t.targetWalletName }} }
              </td>
              <td class="muted wide">{{ t.categoryName ?? '—' }}</td>
              <td class="money-cell" [class.pos]="sign(t) > 0" [class.neg]="sign(t) < 0">
                {{ sign(t) < 0 ? '−' : sign(t) > 0 ? '+' : '' }}{{ abs(t.amount) | brl }}
              </td>
              <td class="actions wide" (click)="$event.stopPropagation()">
                @if (t.status === 'PENDENTE') { <button class="btn small" type="button" (click)="confirmTx(t)">Confirmar</button> }
                <button class="btn ghost small" type="button" (click)="editTx(t)">Editar</button>
              </td>
            </tr>
          } @empty {
            <tr><td colspan="6" class="empty">{{ loading() ? 'Carregando…' : 'Nenhum lançamento neste período.' }}</td></tr>
          }
        </tbody>
      </table>
    </div>

    @if (form(); as f) {
      <div class="backdrop" (click)="form.set(null)">
        <form class="card modal wide" (click)="$event.stopPropagation()" (ngSubmit)="save(f)">
          <h2>{{ f.id ? 'Editar lançamento' : 'Novo lançamento' }}</h2>
          @if (formError()) { <div class="error">{{ formError() }}</div> }
          <div class="field">
            <div class="segmented" role="group" aria-label="Tipo">
              @for (k of kinds; track k) {
                <button type="button" [class.active]="f.kind === k" (click)="setKind(f, k)">{{ kindLabel[k] }}</button>
              }
            </div>
            @if (f.kind === 'AJUSTE') { <p class="hint">Valor positivo aumenta o saldo; negativo diminui.</p> }
            @if (f.kind === 'TRANSFERENCIA') { <p class="hint">Entre carteiras suas: não conta como receita nem despesa.</p> }
          </div>
          <div class="row">
            <div class="field">
              <label for="td">Data</label>
              <input id="td" name="td" type="date" [(ngModel)]="f.date" required>
            </div>
            <div class="field">
              <label for="ta">Valor</label>
              <span class="money"><input id="ta" name="ta" type="number" step="0.01" [(ngModel)]="f.amount" required></span>
            </div>
            <div class="field">
              <label for="ts">Situação</label>
              <select id="ts" name="ts" [(ngModel)]="f.status">
                <option value="CONFIRMADO">Confirmado (afeta o saldo)</option>
                <option value="PENDENTE">Pendente (revisar depois)</option>
                <option value="CANCELADO">Cancelado</option>
              </select>
            </div>
          </div>
          <div class="field">
            <label for="tde">Descrição</label>
            <input id="tde" name="tde" [(ngModel)]="f.description" maxlength="200" required placeholder="Ex.: Supermercado, Salário, Claude Pro">
          </div>
          <div class="row">
            <div class="field">
              <label for="tw">{{ f.kind === 'TRANSFERENCIA' ? 'De (carteira de origem)' : 'Carteira' }}</label>
              <select id="tw" name="tw" [(ngModel)]="f.walletId" required>
                <option value="" disabled>Escolha…</option>
                @for (g of walletGroups(); track g.account) {
                  <optgroup [label]="g.account">
                    @for (w of g.wallets; track w.id) { <option [value]="w.id">{{ w.label }}</option> }
                  </optgroup>
                }
              </select>
            </div>
            @if (f.kind === 'TRANSFERENCIA') {
              <div class="field">
                <label for="tt">Para (carteira de destino)</label>
                <select id="tt" name="tt" [(ngModel)]="f.targetWalletId" required>
                  <option [ngValue]="null" disabled>Escolha…</option>
                  @for (g of walletGroups(); track g.account) {
                    <optgroup [label]="g.account">
                      @for (w of g.wallets; track w.id) {
                        <option [value]="w.id" [disabled]="w.id === f.walletId">{{ w.label }}</option>
                      }
                    </optgroup>
                  }
                </select>
              </div>
            }
            @if (f.kind === 'RECEITA' || f.kind === 'DESPESA') {
              <div class="field">
                <label for="tc">Categoria</label>
                <select id="tc" name="tc" [(ngModel)]="f.categoryId">
                  <option [ngValue]="null">Sem categoria</option>
                  @for (g of categoryGroups(f.kind); track g.parent.id) {
                    <option [value]="g.parent.id">{{ g.parent.name }}</option>
                    @for (c of g.children; track c.id) { <option [value]="c.id">&nbsp;&nbsp;› {{ c.name }}</option> }
                  }
                </select>
              </div>
            }
          </div>
          <div class="field">
            <label for="tn">Observações</label>
            <textarea id="tn" name="tn" [(ngModel)]="f.notes" maxlength="1000" rows="2"></textarea>
          </div>
          <div class="form-actions">
            @if (f.id) { <button class="btn danger" type="button" (click)="remove(f)">Excluir</button> }
            <span class="spacer"></span>
            <button class="btn ghost" type="button" (click)="form.set(null)">Cancelar</button>
            @if (!f.id) { <button class="btn ghost" type="button" (click)="save(f, true)" [disabled]="busy()">Salvar e novo</button> }
            <button class="btn" type="submit" [disabled]="busy()">Salvar</button>
          </div>
        </form>
      </div>
    }
  `,
  styles: `
    .kpis.small .kpi .value { font-size: 18px; }
    tr.dim td:not(.actions) { opacity: .65; }
    tr.clickable { cursor: pointer; }
    tr.clickable:hover td { background: var(--surface-2); }
    td .tag { margin-left: 4px; }
    .meta { display: none; font-size: 12px; margin-top: 2px; }
    @media (max-width: 700px) {
      .wide { display: none; }
      .meta { display: block; }
      .kpis.small { grid-template-columns: repeat(3, 1fr); gap: 6px; }
      .kpis.small .kpi { padding: 8px; }
      .kpis.small .kpi .value { font-size: 13px; }
    }
  `,
})
export class TransactionsComponent implements OnInit {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);

  readonly kinds: TransactionKind[] = ['DESPESA', 'RECEITA', 'TRANSFERENCIA', 'AJUSTE'];
  readonly statuses: TransactionStatus[] = ['CONFIRMADO', 'PENDENTE', 'CANCELADO'];
  readonly kindLabel = KIND_LABEL;
  readonly statusLabel = STATUS_LABEL;

  month = signal(currentMonth());
  accountId = '';
  kind: TransactionKind | '' = '';
  status: TransactionStatus | '' = '';
  categoryId = '';
  q = '';

  items = signal<Transaction[]>([]);
  accounts = signal<BankAccount[]>([]);
  categories = signal<Category[]>([]);
  loading = signal(false);
  error = signal('');
  formError = signal('');
  busy = signal(false);
  form = signal<TxForm | null>(null);

  totals = computed(() => {
    let income = 0, expense = 0;
    for (const t of this.items()) {
      if (t.status !== 'CONFIRMADO') continue;
      if (t.kind === 'RECEITA') income += t.amount;
      if (t.kind === 'DESPESA') expense += t.amount;
    }
    return { income, expense };
  });

  walletGroups = computed<WalletGroup[]>(() => this.accounts()
    .filter(a => !a.archived)
    .map(a => ({
      account: a.name,
      wallets: a.wallets.filter(w => !w.archived).map(w => ({ id: w.id, label: a.wallets.length > 1 ? `${a.name} · ${w.name}` : a.name })),
    }))
    .filter(g => g.wallets.length > 0));

  ngOnInit(): void {
    const p = this.route.snapshot.queryParamMap;
    this.accountId = p.get('accountId') ?? '';
    this.status = (p.get('status') as TransactionStatus | null) ?? '';
    this.api.accounts().subscribe({ next: a => this.accounts.set(a), error: e => this.error.set(errorMessage(e)) });
    this.api.categories().subscribe({ next: c => this.categories.set(c), error: e => this.error.set(errorMessage(e)) });
    this.load();
  }

  setMonth(m: string): void {
    this.month.set(m);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    const { from, to } = monthRange(this.month());
    this.api.transactions({ from, to, accountId: this.accountId, kind: this.kind, status: this.status,
      categoryId: this.categoryId, q: this.q }).subscribe({
      next: list => { this.items.set(list); this.loading.set(false); this.error.set(''); },
      error: e => { this.error.set(errorMessage(e)); this.loading.set(false); },
    });
  }

  categoryGroups(kind: string): CategoryGroup[] {
    const all = this.categories().filter(c => c.kind === kind && !c.archived);
    return all.filter(c => !c.parentId).map(parent => ({ parent, children: all.filter(c => c.parentId === parent.id) }));
  }

  /** +1 entra, −1 sai, 0 neutro (transferência no filtro geral). */
  sign(t: Transaction): number {
    if (t.kind === 'RECEITA') return 1;
    if (t.kind === 'DESPESA') return -1;
    if (t.kind === 'AJUSTE') return t.amount >= 0 ? 1 : -1;
    if (this.accountId) {
      const own = this.accounts().find(a => a.id === this.accountId)?.wallets.map(w => w.id) ?? [];
      const out = own.includes(t.walletId), inn = !!t.targetWalletId && own.includes(t.targetWalletId);
      return out && !inn ? -1 : inn && !out ? 1 : 0;
    }
    return 0;
  }

  abs(n: number): number {
    return Math.abs(n);
  }

  newTx(kind: TransactionKind): void {
    this.formError.set('');
    const firstWallet = this.walletGroups()[0]?.wallets[0]?.id ?? '';
    this.form.set({ id: null, kind, status: 'CONFIRMADO', date: isoDate(), description: '', amount: 0,
      walletId: this.accountId ? (this.accounts().find(a => a.id === this.accountId)?.wallets[0]?.id ?? firstWallet) : firstWallet,
      targetWalletId: null, categoryId: null, notes: null });
  }

  editTx(t: Transaction): void {
    this.formError.set('');
    this.form.set({ id: t.id, kind: t.kind, status: t.status, date: t.date, description: t.description, amount: t.amount,
      walletId: t.walletId, targetWalletId: t.targetWalletId, categoryId: t.categoryId, notes: t.notes });
  }

  setKind(f: TxForm, k: TransactionKind): void {
    if (f.kind !== k) f.categoryId = null;
    f.kind = k;
    if (k !== 'TRANSFERENCIA') f.targetWalletId = null;
  }

  save(f: TxForm, again = false): void {
    const amount = Number(f.amount);
    if (!f.description.trim()) return this.formError.set('Informe a descrição.');
    if (!amount) return this.formError.set('Informe um valor diferente de zero.');
    if (f.kind !== 'AJUSTE' && amount < 0) return this.formError.set('Informe o valor positivo; o tipo define se entra ou sai.');
    if (!f.walletId) return this.formError.set('Escolha a carteira.');
    if (f.kind === 'TRANSFERENCIA' && !f.targetWalletId) return this.formError.set('Escolha a carteira de destino.');
    this.busy.set(true);
    const { id, ...body } = f;
    const req: TransactionRequest = { ...body, amount, notes: f.notes || null };
    const call = id ? this.api.updateTransaction(id, req) : this.api.createTransaction(req);
    call.subscribe({
      next: () => {
        this.busy.set(false);
        if (again) {
          const keep = { kind: f.kind, walletId: f.walletId, date: f.date };
          this.newTx(keep.kind);
          const nf = this.form();
          if (nf) { nf.walletId = keep.walletId; nf.date = keep.date; }
        } else {
          this.form.set(null);
        }
        this.load();
      },
      error: e => { this.busy.set(false); this.formError.set(errorMessage(e)); },
    });
  }

  confirmTx(t: Transaction): void {
    this.api.updateTransaction(t.id, { kind: t.kind, status: 'CONFIRMADO', date: t.date, description: t.description,
      amount: t.amount, walletId: t.walletId, targetWalletId: t.targetWalletId, categoryId: t.categoryId, notes: t.notes })
      .subscribe({ next: () => this.load(), error: e => this.error.set(errorMessage(e)) });
  }

  remove(f: TxForm): void {
    if (!f.id || !confirm('Excluir este lançamento?')) return;
    this.api.deleteTransaction(f.id).subscribe({
      next: () => { this.form.set(null); this.load(); },
      error: e => this.formError.set(errorMessage(e)),
    });
  }
}
