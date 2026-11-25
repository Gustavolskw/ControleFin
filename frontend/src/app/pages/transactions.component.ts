import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../core/auth.interceptor';
import { BrlPipe, DateBrPipe, MonthBrPipe, currentMonth, isoDate, monthRange } from '../core/format';
import {
  BankAccount, Card, Category, KIND_LABEL, STATUS_LABEL, Transaction, TransactionKind, TransactionRequest,
  TransactionStatus,
} from '../core/models';
import { MonthPickerComponent } from '../shared/month-picker.component';

/** IOF em compras internacionais com cartão (desde 2025). */
const IOF_RATE = 0.035;

interface TxForm {
  id: string | null;
  kind: TransactionKind;
  status: TransactionStatus;
  date: string;
  description: string;
  amount: number;
  /** "w:<carteira>" | "c:<cartão>:c" (crédito) | "c:<cartão>:d" (débito/voucher). */
  payWith: string;
  targetWalletId: string | null;
  categoryId: string | null;
  notes: string | null;
  installments: number;
  consumesLimit: boolean;
  foreign: boolean;
  originalCurrency: string;
  originalAmount: number | null;
  exchangeRate: number | null;
  iof: number | null;
  invoiceId: string | null;
  /** Compra parcelada já lançada: só descrição, categoria, observações e situação mudam. */
  locked: boolean;
  installmentCount: number | null;
}

interface Option { value: string; label: string; }
interface OptionGroup { label: string; options: Option[]; }
interface CategoryGroup { parent: Category; children: Category[]; }

/** Lançamentos: receitas, despesas (em carteira ou cartão), transferências, ajustes, pagamentos de fatura e estornos. */
@Component({
  selector: 'app-transactions',
  imports: [FormsModule, RouterLink, BrlPipe, DateBrPipe, MonthBrPipe, MonthPickerComponent],
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
        <label for="fcd">Cartão</label>
        <select id="fcd" [(ngModel)]="cardId" (ngModelChange)="load()">
          <option value="">Todos</option>
          @for (c of cards(); track c.id) { <option [value]="c.id">{{ c.name }}</option> }
        </select>
      </div>
      <div class="field">
        <label for="fk">Tipo</label>
        <select id="fk" [(ngModel)]="kind" (ngModelChange)="load()">
          <option value="">Todos</option>
          @for (k of allKinds; track k) { <option [value]="k">{{ kindLabel[k] }}</option> }
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
        <thead><tr><th>Data</th><th>Descrição</th><th class="wide">Conta / cartão</th><th class="wide">Categoria</th><th class="r">Valor</th><th class="wide"></th></tr></thead>
        <tbody>
          @for (t of items(); track t.id) {
            <tr class="clickable" [class.dim]="t.status !== 'CONFIRMADO'" (click)="editTx(t)">
              <td>{{ t.date | dateBr }}</td>
              <td>
                {{ t.description }}
                @if (t.installmentCount) { <span class="tag">{{ t.installmentNumber }}/{{ t.installmentCount }}</span> }
                @if (t.status !== 'CONFIRMADO') { <span class="tag" [class.warn]="t.status === 'PENDENTE'">{{ statusLabel[t.status] }}</span> }
                @if (t.kind !== 'RECEITA' && t.kind !== 'DESPESA') { <span class="tag" [class.ok]="t.kind === 'ESTORNO'">{{ kindLabel[t.kind] }}</span> }
                @if (t.originalCurrency) { <span class="tag">{{ t.originalCurrency }} {{ t.originalAmount }}</span> }
                @if (t.origin === 'API') { <span class="tag accent" title="Enviado pelo Claude (API externa)">Claude</span> }
                <div class="meta muted">{{ where(t) }}@if (t.categoryName) { · {{ t.categoryName }} }</div>
              </td>
              <td class="muted wide">
                {{ where(t) }}
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
            @if (f.kind === 'PAGAMENTO_FATURA' || f.kind === 'ESTORNO') {
              <span class="tag ok">{{ kindLabel[f.kind] }}</span>
              @if (f.kind === 'PAGAMENTO_FATURA') { <p class="hint">Sai da carteira e abate a fatura; não é despesa.</p> }
            } @else {
              <div class="segmented" role="group" aria-label="Tipo">
                @for (k of kinds; track k) {
                  <button type="button" [class.active]="f.kind === k" (click)="setKind(f, k)" [disabled]="f.locked">{{ kindLabel[k] }}</button>
                }
              </div>
              @if (f.kind === 'AJUSTE') { <p class="hint">Valor positivo aumenta o saldo; negativo diminui.</p> }
              @if (f.kind === 'TRANSFERENCIA') { <p class="hint">Entre carteiras suas: não conta como receita nem despesa.</p> }
            }
          </div>
          @if (f.locked) {
            <div class="warning">Compra parcelada em {{ f.installmentCount }}x: aqui você muda descrição, categoria,
              observações e situação de todas as parcelas. Para mudar valor, data ou cartão, estorne ou exclua e lance de novo.</div>
          }

          @if (f.kind === 'DESPESA') {
            <div class="field">
              <label for="tp">Pagar com</label>
              <select id="tp" name="tp" [(ngModel)]="f.payWith" (ngModelChange)="onPayWith(f)" [disabled]="f.locked" required>
                <option value="" disabled>Escolha…</option>
                @for (g of payGroups(); track g.label) {
                  <optgroup [label]="g.label">
                    @for (o of g.options; track o.value) { <option [value]="o.value">{{ o.label }}</option> }
                  </optgroup>
                }
              </select>
            </div>
          }

          <div class="row">
            <div class="field">
              <label for="td">{{ isCredit(f) ? 'Data da compra' : 'Data' }}</label>
              <input id="td" name="td" type="date" [(ngModel)]="f.date" [disabled]="f.locked" required>
            </div>
            <div class="field">
              <label for="ta">{{ isCredit(f) && f.installments > 1 ? 'Valor total' : 'Valor' }}{{ f.foreign ? ' em R$' : '' }}</label>
              <span class="money"><input id="ta" name="ta" type="number" step="0.01" [(ngModel)]="f.amount" [disabled]="f.locked" required></span>
            </div>
            @if (isCredit(f)) {
              <div class="field">
                <label for="ti">Parcelas</label>
                <input id="ti" name="ti" type="number" min="1" max="72" [(ngModel)]="f.installments" [disabled]="!!f.id || f.foreign">
                @if (f.installments > 1 && f.amount > 0) { <p class="hint">{{ f.installments }}x de ~{{ f.amount / f.installments | brl }}</p> }
              </div>
            }
            <div class="field">
              <label for="ts">Situação</label>
              <select id="ts" name="ts" [(ngModel)]="f.status">
                <option value="CONFIRMADO">Confirmado</option>
                <option value="PENDENTE">Pendente (revisar depois)</option>
                <option value="CANCELADO">Cancelado</option>
              </select>
            </div>
          </div>

          @if (isCredit(f)) {
            <label class="check"><input type="checkbox" name="tl" [(ngModel)]="f.consumesLimit"> Reserva o limite do cartão</label>
            <p class="hint" style="margin: 2px 0 10px">Desmarque para cobranças que entram na fatura mas deixam o crédito
              livre (ex.: assinatura do Claude).</p>
          }

          @if (f.kind === 'DESPESA' || (f.kind === 'ESTORNO' && f.foreign)) {
            <label class="check"><input type="checkbox" name="tf" [(ngModel)]="f.foreign" (ngModelChange)="onForeign(f)" [disabled]="f.locked"> Compra em moeda estrangeira</label>
            @if (f.foreign) {
              <div class="row foreign">
                <div class="field">
                  <label for="tfc">Moeda</label>
                  <input id="tfc" name="tfc" [(ngModel)]="f.originalCurrency" maxlength="3" placeholder="USD" style="text-transform: uppercase">
                </div>
                <div class="field">
                  <label for="tfa">Valor na moeda</label>
                  <input id="tfa" name="tfa" type="number" step="0.01" min="0" [(ngModel)]="f.originalAmount" (ngModelChange)="recalc(f)">
                </div>
                <div class="field">
                  <label for="tfr">Cotação (R$)</label>
                  <input id="tfr" name="tfr" type="number" step="0.0001" min="0" [(ngModel)]="f.exchangeRate" (ngModelChange)="recalc(f)">
                </div>
                <div class="field">
                  <label for="tfi">IOF (R$)</label>
                  <input id="tfi" name="tfi" type="number" step="0.01" min="0" [(ngModel)]="f.iof" (ngModelChange)="recalc(f)">
                  <button type="button" class="link" (click)="fillIof(f)">Calcular 3,5%</button>
                </div>
              </div>
              <p class="hint" style="margin-top: -4px">Valor em reais = valor na moeda × cotação + IOF. Ajuste se a fatura mostrar outro valor.</p>
            }
          }

          <div class="field">
            <label for="tde">Descrição</label>
            <input id="tde" name="tde" [(ngModel)]="f.description" maxlength="200" required placeholder="Ex.: Supermercado, Salário, Claude Pro">
          </div>
          <div class="row">
            @if (f.kind !== 'DESPESA' && !(f.kind === 'ESTORNO' && cardOf(f))) {
              <div class="field">
                <label for="tw">{{ f.kind === 'TRANSFERENCIA' ? 'De (carteira de origem)' : f.kind === 'PAGAMENTO_FATURA' ? 'Pago com' : 'Carteira' }}</label>
                <select id="tw" name="tw" [(ngModel)]="f.payWith" required>
                  <option value="" disabled>Escolha…</option>
                  @for (g of walletGroups(); track g.label) {
                    <optgroup [label]="g.label">
                      @for (o of g.options; track o.value) { <option [value]="o.value">{{ o.label }}</option> }
                    </optgroup>
                  }
                </select>
              </div>
            }
            @if (f.kind === 'ESTORNO' && cardOf(f); as c) {
              <div class="field"><span class="label">Cartão</span><div class="static">{{ c.name }}</div></div>
            }
            @if (f.kind === 'TRANSFERENCIA') {
              <div class="field">
                <label for="tt">Para (carteira de destino)</label>
                <select id="tt" name="tt" [(ngModel)]="f.targetWalletId" required>
                  <option [ngValue]="null" disabled>Escolha…</option>
                  @for (g of walletGroups(); track g.label) {
                    <optgroup [label]="g.label">
                      @for (o of g.options; track o.value) {
                        <option [value]="o.value.slice(2)" [disabled]="o.value === f.payWith">{{ o.label }}</option>
                      }
                    </optgroup>
                  }
                </select>
              </div>
            }
            @if (f.kind === 'RECEITA' || f.kind === 'DESPESA' || f.kind === 'ESTORNO') {
              <div class="field">
                <label for="tc">Categoria</label>
                <select id="tc" name="tc" [(ngModel)]="f.categoryId">
                  <option [ngValue]="null">Sem categoria</option>
                  @for (g of categoryGroups(f.kind === 'RECEITA' ? 'RECEITA' : 'DESPESA'); track g.parent.id) {
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
          @if (f.id && editing(); as t) {
            @if (t.invoiceId && t.creditLineId) {
              <p class="hint">Na fatura de {{ t.invoiceReference | monthBr }} ·
                <a [routerLink]="['/cartoes', t.creditLineId, 'faturas']" [queryParams]="{ fatura: t.invoiceId }">ver fatura</a></p>
            }
          }
          <div class="form-actions">
            @if (f.id) { <button class="btn danger" type="button" (click)="remove(f)">Excluir</button> }
            @if (f.id && f.kind === 'DESPESA' && editing()?.status !== 'CANCELADO') {
              <button class="btn ghost" type="button" (click)="reverse(f)" title="Desfaz a compra sem apagar o histórico">Estornar</button>
            }
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
    .static { padding: 8px 0; font-weight: 600; }
    .label { font-size: 13px; font-weight: 500; color: var(--muted); }
    .foreign .field { min-width: 110px; }
    button.link { background: none; border: 0; padding: 2px 0; color: var(--accent); cursor: pointer; font-size: 12px; }
    .warning { margin-bottom: 12px; }
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
  private router = inject(Router);

  readonly kinds: TransactionKind[] = ['DESPESA', 'RECEITA', 'TRANSFERENCIA', 'AJUSTE'];
  readonly allKinds: TransactionKind[] = ['DESPESA', 'RECEITA', 'TRANSFERENCIA', 'AJUSTE', 'PAGAMENTO_FATURA', 'ESTORNO'];
  readonly statuses: TransactionStatus[] = ['CONFIRMADO', 'PENDENTE', 'CANCELADO'];
  readonly kindLabel = KIND_LABEL;
  readonly statusLabel = STATUS_LABEL;

  month = signal(currentMonth());
  accountId = '';
  cardId = '';
  kind: TransactionKind | '' = '';
  status: TransactionStatus | '' = '';
  categoryId = '';
  q = '';

  items = signal<Transaction[]>([]);
  accounts = signal<BankAccount[]>([]);
  cards = signal<Card[]>([]);
  categories = signal<Category[]>([]);
  loading = signal(false);
  error = signal('');
  formError = signal('');
  busy = signal(false);
  form = signal<TxForm | null>(null);
  editing = signal<Transaction | null>(null);

  totals = computed(() => {
    let income = 0, expense = 0;
    for (const t of this.items()) {
      if (t.status !== 'CONFIRMADO') continue;
      if (t.kind === 'RECEITA') income += t.amount;
      if (t.kind === 'DESPESA') expense += t.amount;
      if (t.kind === 'ESTORNO') expense -= t.amount;
    }
    return { income, expense };
  });

  walletGroups = computed<OptionGroup[]>(() => this.accounts()
    .filter(a => !a.archived)
    .map(a => ({
      label: a.name,
      options: a.wallets.filter(w => !w.archived).map(w => ({ value: 'w:' + w.id, label: a.wallets.length > 1 ? `${a.name} · ${w.name}` : a.name })),
    }))
    .filter(g => g.options.length > 0));

  /** Formas de pagamento de uma despesa: cartões de crédito, débito/voucher e carteiras. */
  payGroups = computed<OptionGroup[]>(() => {
    const active = this.cards().filter(c => !c.archived);
    const credit = active.filter(c => c.function === 'CREDITO_DEBITO')
      .map(c => ({ value: `c:${c.id}:c`, label: `${c.name} — crédito` }));
    const debit = active.filter(c => !!c.walletId)
      .map(c => ({ value: `c:${c.id}:d`, label: `${c.name} — ${c.function === 'VOUCHER_ALIMENTACAO' ? 'voucher' : 'débito'}` }));
    const groups: OptionGroup[] = [];
    if (credit.length) groups.push({ label: 'Cartão de crédito', options: credit });
    if (debit.length) groups.push({ label: 'Cartão de débito / voucher', options: debit });
    const wallets = this.walletGroups().flatMap(g => g.options);
    if (wallets.length) groups.push({ label: 'Conta (Pix, dinheiro, boleto…)', options: wallets });
    return groups;
  });

  ngOnInit(): void {
    const p = this.route.snapshot.queryParamMap;
    this.accountId = p.get('accountId') ?? '';
    this.cardId = p.get('cardId') ?? '';
    this.status = (p.get('status') as TransactionStatus | null) ?? '';
    const novo = p.get('novo');
    this.api.accounts().subscribe({ next: a => this.accounts.set(a), error: e => this.error.set(errorMessage(e)) });
    this.api.categories().subscribe({ next: c => this.categories.set(c), error: e => this.error.set(errorMessage(e)) });
    this.api.cards().subscribe({
      next: c => {
        this.cards.set(c);
        const card = novo ? c.find(x => x.id === novo) : null;
        if (card) {
          this.newTx('DESPESA');
          const f = this.form();
          if (f) f.payWith = `c:${card.id}:${card.function === 'CREDITO_DEBITO' ? 'c' : 'd'}`;
          this.router.navigate([], { queryParams: { novo: null }, queryParamsHandling: 'merge', replaceUrl: true });
        }
      },
      error: e => this.error.set(errorMessage(e)),
    });
    this.load();
  }

  setMonth(m: string): void {
    this.month.set(m);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    const { from, to } = monthRange(this.month());
    this.api.transactions({ from, to, accountId: this.accountId, cardId: this.cardId, kind: this.kind,
      status: this.status, categoryId: this.categoryId, q: this.q }).subscribe({
      next: list => { this.items.set(list); this.loading.set(false); this.error.set(''); },
      error: e => { this.error.set(errorMessage(e)); this.loading.set(false); },
    });
  }

  categoryGroups(kind: string): CategoryGroup[] {
    const all = this.categories().filter(c => c.kind === kind && !c.archived);
    return all.filter(c => !c.parentId).map(parent => ({ parent, children: all.filter(c => c.parentId === parent.id) }));
  }

  /** Onde o lançamento acontece (conta/carteira ou cartão/fatura). */
  where(t: Transaction): string {
    if (t.invoiceId && t.kind !== 'PAGAMENTO_FATURA') {
      return `${t.cardName ?? t.creditLineName} · crédito`;
    }
    const base = `${t.accountName} · ${t.walletName}`;
    if (t.kind === 'PAGAMENTO_FATURA') return `${base} → fatura ${t.creditLineName}`;
    return t.cardName ? `${base} (${t.cardName})` : base;
  }

  /** +1 entra, −1 sai, 0 neutro (transferência no filtro geral). Compras no crédito aparecem como saída. */
  sign(t: Transaction): number {
    if (t.kind === 'RECEITA' || t.kind === 'ESTORNO') return 1;
    if (t.kind === 'DESPESA' || t.kind === 'PAGAMENTO_FATURA') return -1;
    if (t.kind === 'AJUSTE') return t.amount >= 0 ? 1 : -1;
    if (this.accountId) {
      const own = this.accounts().find(a => a.id === this.accountId)?.wallets.map(w => w.id) ?? [];
      const out = !!t.walletId && own.includes(t.walletId), inn = !!t.targetWalletId && own.includes(t.targetWalletId);
      return out && !inn ? -1 : inn && !out ? 1 : 0;
    }
    return 0;
  }

  abs(n: number): number {
    return Math.abs(n);
  }

  isCredit(f: TxForm): boolean {
    return (f.kind === 'DESPESA' || f.kind === 'ESTORNO') && f.payWith.startsWith('c:') && f.payWith.endsWith(':c');
  }

  cardOf(f: TxForm): Card | null {
    if (!f.payWith.startsWith('c:')) return null;
    const id = f.payWith.split(':')[1];
    return this.cards().find(c => c.id === id) ?? null;
  }

  private blank(kind: TransactionKind): TxForm {
    const firstWallet = this.walletGroups()[0]?.options[0]?.value ?? '';
    const accWallet = this.accountId ? this.accounts().find(a => a.id === this.accountId)?.wallets[0]?.id : null;
    return { id: null, kind, status: 'CONFIRMADO', date: isoDate(), description: '', amount: 0,
      payWith: accWallet ? 'w:' + accWallet : firstWallet, targetWalletId: null, categoryId: null, notes: null,
      installments: 1, consumesLimit: true, foreign: false, originalCurrency: 'USD', originalAmount: null,
      exchangeRate: null, iof: null, invoiceId: null, locked: false, installmentCount: null };
  }

  newTx(kind: TransactionKind): void {
    this.formError.set('');
    this.editing.set(null);
    this.form.set(this.blank(kind));
  }

  editTx(t: Transaction): void {
    this.formError.set('');
    this.editing.set(t);
    let payWith = t.walletId ? 'w:' + t.walletId : '';
    if (t.cardId && t.kind !== 'PAGAMENTO_FATURA') payWith = `c:${t.cardId}:${t.invoiceId ? 'c' : 'd'}`;
    const group = !!t.installmentGroup;
    this.form.set({ id: t.id, kind: t.kind, status: t.status, date: t.date, description: t.description,
      amount: t.amount, payWith, targetWalletId: t.targetWalletId, categoryId: t.categoryId, notes: t.notes,
      installments: t.installmentCount ?? 1, consumesLimit: t.consumesLimit, foreign: !!t.originalCurrency,
      originalCurrency: t.originalCurrency ?? 'USD', originalAmount: t.originalAmount, exchangeRate: t.exchangeRate,
      iof: t.iof, invoiceId: t.invoiceId, locked: group, installmentCount: t.installmentCount });
  }

  setKind(f: TxForm, k: TransactionKind): void {
    if (f.kind !== k) f.categoryId = null;
    f.kind = k;
    if (k !== 'TRANSFERENCIA') f.targetWalletId = null;
    if (k !== 'DESPESA') {
      f.foreign = false;
      f.installments = 1;
      if (!f.payWith.startsWith('w:')) f.payWith = this.walletGroups()[0]?.options[0]?.value ?? '';
    }
  }

  onPayWith(f: TxForm): void {
    if (!this.isCredit(f)) f.installments = 1;
  }

  onForeign(f: TxForm): void {
    if (f.foreign) {
      f.installments = 1;
      this.recalc(f);
    }
  }

  /** Valor em reais = valor na moeda × cotação + IOF. */
  recalc(f: TxForm): void {
    const orig = Number(f.originalAmount), rate = Number(f.exchangeRate);
    if (!(orig > 0 && rate > 0)) return;
    f.amount = Math.round((orig * rate + (Number(f.iof) || 0)) * 100) / 100;
  }

  fillIof(f: TxForm): void {
    const orig = Number(f.originalAmount), rate = Number(f.exchangeRate);
    if (!(orig > 0 && rate > 0)) return this.formError.set('Informe o valor na moeda e a cotação.');
    f.iof = Math.round(orig * rate * IOF_RATE * 100) / 100;
    this.recalc(f);
  }

  private toRequest(f: TxForm): TransactionRequest {
    const [type, id, mode] = f.payWith.split(':');
    const card = type === 'c';
    const credit = card && mode === 'c';
    const foreign = f.foreign && (f.kind === 'DESPESA' || f.kind === 'ESTORNO');
    const keepInvoice = f.kind === 'PAGAMENTO_FATURA' || (credit && !!f.id && f.date === this.editing()?.date);
    return {
      kind: f.kind, status: f.status, date: f.date, description: f.description, amount: Number(f.amount),
      walletId: card ? null : id, targetWalletId: f.kind === 'TRANSFERENCIA' ? f.targetWalletId : null,
      categoryId: f.categoryId, notes: f.notes || null,
      cardId: card ? id : null, credit: card ? credit : null,
      installments: credit && !f.id ? Number(f.installments) || 1 : null,
      consumesLimit: credit ? f.consumesLimit : null,
      invoiceId: keepInvoice ? f.invoiceId : null,
      originalCurrency: foreign ? f.originalCurrency.trim().toUpperCase() : null,
      originalAmount: foreign ? Number(f.originalAmount) : null,
      exchangeRate: foreign ? Number(f.exchangeRate) : null,
      iof: foreign && f.iof != null && String(f.iof) !== '' ? Number(f.iof) : null,
    };
  }

  save(f: TxForm, again = false): void {
    const amount = Number(f.amount);
    if (!f.description.trim()) return this.formError.set('Informe a descrição.');
    if (!amount) return this.formError.set('Informe um valor diferente de zero.');
    if (f.kind !== 'AJUSTE' && amount < 0) return this.formError.set('Informe o valor positivo; o tipo define se entra ou sai.');
    if (!f.payWith) return this.formError.set(f.kind === 'DESPESA' ? 'Escolha como pagou.' : 'Escolha a carteira.');
    if (f.kind === 'TRANSFERENCIA' && !f.targetWalletId) return this.formError.set('Escolha a carteira de destino.');
    if (this.isCredit(f) && !(Number(f.installments) >= 1 && Number(f.installments) <= 72)) {
      return this.formError.set('Parcelas: de 1 a 72.');
    }
    if (f.foreign && (!/^[A-Za-z]{3}$/.test(f.originalCurrency.trim()) || !(Number(f.originalAmount) > 0) || !(Number(f.exchangeRate) > 0))) {
      return this.formError.set('Na compra internacional, informe a moeda (ex.: USD), o valor na moeda e a cotação.');
    }
    this.busy.set(true);
    const req = this.toRequest(f);
    const call = f.id ? this.api.updateTransaction(f.id, req) : this.api.createTransaction(req);
    call.subscribe({
      next: () => {
        this.busy.set(false);
        if (again) {
          const keep = { kind: f.kind, payWith: f.payWith, date: f.date };
          this.newTx(keep.kind);
          const nf = this.form();
          if (nf) { nf.payWith = keep.payWith; nf.date = keep.date; }
        } else {
          this.form.set(null);
        }
        this.load();
      },
      error: e => { this.busy.set(false); this.formError.set(errorMessage(e)); },
    });
  }

  confirmTx(t: Transaction): void {
    this.editTx(t);
    const f = this.form();
    this.form.set(null);
    if (!f) return;
    f.status = 'CONFIRMADO';
    this.api.updateTransaction(t.id, this.toRequest(f))
      .subscribe({ next: () => this.load(), error: e => this.error.set(errorMessage(e)) });
  }

  reverse(f: TxForm): void {
    if (!f.id) return;
    const msg = this.isCredit(f)
      ? 'Estornar esta compra? O que ainda não fechou é cancelado; o que já foi cobrado volta como crédito na fatura aberta.'
      : 'Estornar esta compra? Se ainda não aconteceu (pendente/data futura) ela é cancelada; senão entra um estorno na carteira hoje.';
    if (!confirm(msg)) return;
    this.api.reverseTransaction(f.id).subscribe({
      next: () => { this.form.set(null); this.load(); },
      error: e => this.formError.set(errorMessage(e)),
    });
  }

  remove(f: TxForm): void {
    const msg = f.locked ? `Excluir as ${f.installmentCount} parcelas desta compra? (Para compra real desfeita, prefira Estornar.)`
      : 'Excluir este lançamento?';
    if (!f.id || !confirm(msg)) return;
    this.api.deleteTransaction(f.id).subscribe({
      next: () => { this.form.set(null); this.load(); },
      error: e => this.formError.set(errorMessage(e)),
    });
  }
}
