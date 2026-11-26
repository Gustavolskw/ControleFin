import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../core/auth.interceptor';
import { BrlPipe, DateBrPipe, currentMonth } from '../core/format';
import {
  BankAccount, Card, Category, Frequency, OCCURRENCE_LABEL, Occurrence, Recurrence, RecurrenceMonth,
  RecurrenceRequest, Transaction, TransactionStatus,
} from '../core/models';
import { MonthPickerComponent } from '../shared/month-picker.component';

const IOF_RATE = 0.035;

interface RecForm {
  id: string | null;
  kind: 'RECEITA' | 'DESPESA';
  description: string;
  amount: number;
  frequency: Frequency;
  intervalCount: number;
  dayOfMonth: number | null;
  startDate: string;
  endDate: string;
  /** "w:<carteira>" | "c:<cartão>:c" | "c:<cartão>:d" */
  payWith: string;
  consumesLimit: boolean;
  categoryId: string | null;
  foreign: boolean;
  originalCurrency: string;
  originalAmount: number | null;
  notes: string;
  active: boolean;
}

interface LaunchForm {
  occ: Occurrence;
  rec: Recurrence;
  date: string;
  amount: number;
  status: TransactionStatus;
  exchangeRate: number | null;
  iof: number | null;
  candidates: Transaction[] | null;
}

interface Option { value: string; label: string; }
interface OptionGroup { label: string; options: Option[]; }
interface CategoryGroup { parent: Category; children: Category[]; }

/**
 * Recorrências (assinaturas, aluguel, salário…): previsto × realizado no mês. Cada ocorrência é lançada uma única
 * vez; antes de lançar, o sistema mostra lançamentos parecidos para vincular em vez de duplicar.
 */
@Component({
  selector: 'app-recurrences',
  imports: [FormsModule, RouterLink, BrlPipe, DateBrPipe, MonthPickerComponent],
  template: `
    <div class="page-head">
      <h1>Recorrências</h1>
      <app-month-picker [month]="month()" (monthChange)="setMonth($event)" />
      <button class="btn" type="button" (click)="newRec()">Nova recorrência</button>
      <p class="sub">Assinaturas, aluguel, salário… O previsto de cada mês vira lançamento quando você confirma, uma única vez por ocorrência.</p>
    </div>
    @if (error()) { <div class="error" role="alert">{{ error() }}</div> }

    @if (data(); as d) {
      <div class="kpis">
        <div class="kpi">
          <div class="label">Despesas previstas</div>
          <div class="value">{{ d.expectedExpense | brl }}</div>
          <div class="detail">lançado {{ d.realizedExpense | brl }}</div>
        </div>
        <div class="kpi">
          <div class="label">Despesas a lançar</div>
          <div class="value neg">{{ d.pendingExpense | brl }}</div>
          <div class="detail">atrasadas + a vencer no mês</div>
        </div>
        <div class="kpi">
          <div class="label">Receitas previstas</div>
          <div class="value pos">{{ d.expectedIncome | brl }}</div>
          <div class="detail">a receber {{ d.pendingIncome | brl }}</div>
        </div>
      </div>

      <section class="card">
        <h2>Ocorrências do mês</h2>
        <div class="table-wrap">
          <table class="data">
            <thead><tr><th class="wide">Data</th><th>Descrição</th><th class="wide">Pago com</th><th class="r">Previsto</th><th class="r wide">Lançado</th><th></th></tr></thead>
            <tbody>
              @for (o of d.occurrences; track o.recurrenceId + o.date) {
                <tr [class.dim]="o.status === 'PULADA'">
                  <td class="wide">{{ o.date | dateBr }}</td>
                  <td>
                    {{ o.description }}
                    <span class="tag" [class.ok]="o.status === 'REALIZADA'" [class.warn]="o.status === 'ATRASADA'">{{ occLabel[o.status] }}</span>
                    @if (o.candidates > 0) { <span class="tag accent" title="Existe lançamento parecido sem vínculo: vincule em vez de lançar de novo">Possível lançamento já feito</span> }
                    @if (o.originalCurrency) { <span class="tag">{{ o.originalCurrency }} {{ o.originalAmount }}</span> }
                    <div class="meta muted">{{ o.date | dateBr }} · {{ o.paidWith }}</div>
                  </td>
                  <td class="muted wide">{{ o.paidWith }}</td>
                  <td class="money-cell" [class.pos]="o.kind === 'RECEITA'">{{ o.kind === 'RECEITA' ? '+' : '−' }}{{ o.expectedAmount | brl }}</td>
                  <td class="money-cell wide">{{ o.actualAmount != null ? (o.actualAmount | brl) : '—' }}</td>
                  <td class="actions">
                    @switch (o.status) {
                      @case ('REALIZADA') { <a class="btn ghost small" routerLink="/lancamentos" [queryParams]="{ q: o.description }">Ver</a> }
                      @case ('PULADA') { <button class="btn ghost small" type="button" (click)="unskip(o)">Desfazer</button> }
                      @default {
                        <button class="btn small" type="button" (click)="openLaunch(o)">Lançar</button>
                        <button class="btn ghost small" type="button" (click)="skip(o)">Pular</button>
                      }
                    }
                  </td>
                </tr>
              } @empty {
                <tr><td colspan="6" class="empty">Nenhuma recorrência neste mês.</td></tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      <section class="card">
        <h2>Cadastro</h2>
        <div class="table-wrap">
          <table class="data">
            <thead><tr><th>Descrição</th><th class="wide">Quando</th><th class="wide">Pago com</th><th class="r">Valor</th><th class="wide">Próxima a lançar</th><th></th></tr></thead>
            <tbody>
              @for (r of d.recurrences; track r.id) {
                <tr [class.dim]="!r.active">
                  <td>{{ r.description }}
                    @if (!r.active) { <span class="tag">Inativa</span> }
                    @if (r.credit && !r.consumesLimit) { <span class="tag" title="Entra na fatura sem reservar limite">Não reserva limite</span> }
                    <div class="meta muted">{{ when(r) }} · {{ r.paidWith }}</div>
                  </td>
                  <td class="wide">{{ when(r) }}</td>
                  <td class="muted wide">{{ r.paidWith }}</td>
                  <td class="money-cell" [class.pos]="r.kind === 'RECEITA'">{{ r.amount | brl }}
                    @if (r.originalCurrency) { <div class="muted small">{{ r.originalCurrency }} {{ r.originalAmount }}</div> }</td>
                  <td class="wide">{{ r.nextDate ? (r.nextDate | dateBr) : '—' }}</td>
                  <td class="actions"><button class="btn ghost small" type="button" (click)="editRec(r)">Editar</button></td>
                </tr>
              } @empty {
                <tr><td colspan="6" class="empty">Nenhuma recorrência cadastrada. Ex.: Claude Pro, aluguel, salário.</td></tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    } @else if (!error()) {
      <p class="muted">Carregando…</p>
    }

    <!-- lançar ocorrência -->
    @if (launch(); as l) {
      <div class="backdrop" (click)="launch.set(null)">
        <form class="card modal" (click)="$event.stopPropagation()" (ngSubmit)="saveLaunch(l)">
          <h2>Lançar — {{ l.occ.description }}</h2>
          <p class="hint" style="margin-top: -8px">Ocorrência de {{ l.occ.date | dateBr }} · {{ l.occ.paidWith }} · previsto {{ l.occ.expectedAmount | brl }}</p>
          @if (formError()) { <div class="error">{{ formError() }}</div> }
          @if (l.candidates === null) {
            <p class="muted">Procurando lançamentos parecidos…</p>
          } @else if (l.candidates.length > 0) {
            <div class="warning">
              <strong>Talvez já esteja lançado.</strong> Vincule em vez de lançar de novo:
              <ul class="cands">
                @for (t of l.candidates; track t.id) {
                  <li>
                    <span>{{ t.date | dateBr }} · {{ t.description }} · <strong>{{ t.amount | brl }}</strong></span>
                    <button class="btn small" type="button" (click)="link(l, t)">Vincular</button>
                  </li>
                }
              </ul>
            </div>
          }
          <div class="row">
            <div class="field">
              <label for="ld">Data</label>
              <input id="ld" name="ld" type="date" [(ngModel)]="l.date" required>
            </div>
            <div class="field">
              <label for="la">Valor{{ l.rec.originalCurrency ? ' em R$' : '' }}</label>
              <span class="money"><input id="la" name="la" type="number" step="0.01" min="0.01" [(ngModel)]="l.amount" required></span>
            </div>
            <div class="field">
              <label for="ls">Situação</label>
              <select id="ls" name="ls" [(ngModel)]="l.status">
                <option value="CONFIRMADO">Confirmado</option>
                <option value="PENDENTE">Pendente</option>
              </select>
            </div>
          </div>
          @if (l.rec.originalCurrency) {
            <div class="row">
              <div class="field">
                <label for="lr">Cotação ({{ l.rec.originalCurrency }} {{ l.rec.originalAmount }})</label>
                <input id="lr" name="lr" type="number" step="0.0001" min="0" [(ngModel)]="l.exchangeRate" (ngModelChange)="recalcLaunch(l)">
              </div>
              <div class="field">
                <label for="li">IOF (R$)</label>
                <input id="li" name="li" type="number" step="0.01" min="0" [(ngModel)]="l.iof" (ngModelChange)="recalcLaunch(l)">
                <button type="button" class="link" (click)="fillIof(l)">Calcular 3,5%</button>
              </div>
            </div>
          }
          <div class="form-actions">
            <span class="spacer"></span>
            <button class="btn ghost" type="button" (click)="launch.set(null)">Cancelar</button>
            <button class="btn" type="submit" [disabled]="busy()">{{ l.candidates?.length ? 'Lançar mesmo assim' : 'Lançar' }}</button>
          </div>
        </form>
      </div>
    }

    <!-- cadastro -->
    @if (form(); as f) {
      <div class="backdrop" (click)="form.set(null)">
        <form class="card modal wide" (click)="$event.stopPropagation()" (ngSubmit)="saveRec(f)">
          <h2>{{ f.id ? 'Editar recorrência' : 'Nova recorrência' }}</h2>
          @if (formError()) { <div class="error">{{ formError() }}</div> }
          <div class="field">
            <div class="segmented" role="group" aria-label="Tipo">
              <button type="button" [class.active]="f.kind === 'DESPESA'" (click)="setKind(f, 'DESPESA')">Despesa</button>
              <button type="button" [class.active]="f.kind === 'RECEITA'" (click)="setKind(f, 'RECEITA')">Receita</button>
            </div>
          </div>
          <div class="row">
            <div class="field" style="flex: 2">
              <label for="rd">Descrição</label>
              <input id="rd" name="rd" [(ngModel)]="f.description" maxlength="200" required placeholder="Ex.: Claude Pro, Aluguel, Salário">
            </div>
            <div class="field">
              <label for="ra">Valor previsto{{ f.foreign ? ' em R$' : '' }}</label>
              <span class="money"><input id="ra" name="ra" type="number" step="0.01" min="0.01" [(ngModel)]="f.amount" required></span>
            </div>
          </div>
          <div class="field">
            <label for="rp">{{ f.kind === 'RECEITA' ? 'Entra em' : 'Pagar com' }}</label>
            <select id="rp" name="rp" [(ngModel)]="f.payWith" required>
              <option value="" disabled>Escolha…</option>
              @for (g of (f.kind === 'RECEITA' ? walletGroups() : payGroups()); track g.label) {
                <optgroup [label]="g.label">
                  @for (o of g.options; track o.value) { <option [value]="o.value">{{ o.label }}</option> }
                </optgroup>
              }
            </select>
          </div>
          @if (isCredit(f)) {
            <label class="check"><input type="checkbox" name="rl" [(ngModel)]="f.consumesLimit"> Reserva o limite do cartão</label>
            <p class="hint" style="margin: 2px 0 10px">Marcado: a próxima cobrança já reserva limite na fatura aberta.
              Desmarcado (ex.: Claude): entra na fatura mas deixa o crédito livre.</p>
          }
          <div class="row">
            <div class="field">
              <label for="rf">Repete</label>
              <select id="rf" name="rf" [(ngModel)]="f.frequency">
                <option value="MENSAL">Todo mês</option>
                <option value="ANUAL">Todo ano</option>
                <option value="SEMANAL">Toda semana</option>
              </select>
            </div>
            <div class="field">
              <label for="ri">A cada</label>
              <input id="ri" name="ri" type="number" min="1" max="12" [(ngModel)]="f.intervalCount">
              <p class="hint">{{ f.frequency === 'SEMANAL' ? 'semana(s)' : f.frequency === 'ANUAL' ? 'ano(s)' : 'mês(es)' }}</p>
            </div>
            @if (f.frequency !== 'SEMANAL') {
              <div class="field">
                <label for="ry">Dia</label>
                <input id="ry" name="ry" type="number" min="1" max="31" [(ngModel)]="f.dayOfMonth">
              </div>
            }
            <div class="field">
              <label for="rs">Início</label>
              <input id="rs" name="rs" type="date" [(ngModel)]="f.startDate" required>
            </div>
            <div class="field">
              <label for="re">Fim (opcional)</label>
              <input id="re" name="re" type="date" [(ngModel)]="f.endDate">
            </div>
          </div>
          @if (f.kind === 'DESPESA') {
            <label class="check"><input type="checkbox" name="rx" [(ngModel)]="f.foreign"> Cobrada em moeda estrangeira</label>
            @if (f.foreign) {
              <div class="row">
                <div class="field">
                  <label for="rc">Moeda</label>
                  <input id="rc" name="rc" [(ngModel)]="f.originalCurrency" maxlength="3" placeholder="USD" style="text-transform: uppercase">
                </div>
                <div class="field">
                  <label for="ro">Valor na moeda</label>
                  <input id="ro" name="ro" type="number" step="0.01" min="0.01" [(ngModel)]="f.originalAmount">
                </div>
              </div>
              <p class="hint" style="margin-top: -4px">O valor em reais acima é a previsão; ao lançar você informa a cotação do mês.</p>
            }
          }
          <div class="row">
            <div class="field">
              <label for="rk">Categoria</label>
              <select id="rk" name="rk" [(ngModel)]="f.categoryId">
                <option [ngValue]="null">Sem categoria</option>
                @for (g of categoryGroups(f.kind); track g.parent.id) {
                  <option [value]="g.parent.id">{{ g.parent.name }}</option>
                  @for (c of g.children; track c.id) { <option [value]="c.id">&nbsp;&nbsp;› {{ c.name }}</option> }
                }
              </select>
            </div>
          </div>
          <div class="field">
            <label for="rn">Observações</label>
            <textarea id="rn" name="rn" [(ngModel)]="f.notes" maxlength="1000" rows="2"></textarea>
          </div>
          @if (f.id) {
            <label class="check"><input type="checkbox" name="rv" [(ngModel)]="f.active"> Ativa (desmarque para parar de prever, mantendo o histórico)</label>
          }
          <div class="form-actions">
            @if (f.id) { <button class="btn danger" type="button" (click)="deleteRec(f)">Excluir</button> }
            <span class="spacer"></span>
            <button class="btn ghost" type="button" (click)="form.set(null)">Cancelar</button>
            <button class="btn" type="submit" [disabled]="busy()">Salvar</button>
          </div>
        </form>
      </div>
    }
  `,
  styles: `
    section.card { margin-bottom: 16px; }
    tr.dim td:not(.actions) { opacity: .6; }
    td .tag { margin-left: 4px; }
    .meta { display: none; font-size: 12px; margin-top: 2px; }
    .small { font-size: 12px; }
    .cands { list-style: none; margin: 8px 0 0; padding: 0; display: grid; gap: 6px; }
    .cands li { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    button.link { background: none; border: 0; padding: 2px 0; color: var(--accent); cursor: pointer; font-size: 12px; }
    .warning { margin-bottom: 12px; }
    @media (max-width: 700px) {
      th.wide, td.wide { display: none; }
      .meta { display: block; }
      td.actions { white-space: normal; }
      td.actions .btn { margin: 2px 0; }
    }
  `,
})
export class RecurrencesComponent implements OnInit {
  private api = inject(ApiService);

  readonly occLabel = OCCURRENCE_LABEL;
  month = signal(currentMonth());
  data = signal<RecurrenceMonth | null>(null);
  accounts = signal<BankAccount[]>([]);
  cards = signal<Card[]>([]);
  categories = signal<Category[]>([]);
  error = signal('');
  formError = signal('');
  busy = signal(false);
  form = signal<RecForm | null>(null);
  launch = signal<LaunchForm | null>(null);

  walletGroups = computed<OptionGroup[]>(() => this.accounts().filter(a => !a.archived)
    .map(a => ({ label: a.name, options: a.wallets.filter(w => !w.archived)
      .map(w => ({ value: 'w:' + w.id, label: a.wallets.length > 1 ? `${a.name} · ${w.name}` : a.name })) }))
    .filter(g => g.options.length > 0));

  payGroups = computed<OptionGroup[]>(() => {
    const active = this.cards().filter(c => !c.archived);
    const credit = active.filter(c => c.function === 'CREDITO_DEBITO').map(c => ({ value: `c:${c.id}:c`, label: `${c.name} — crédito` }));
    const debit = active.filter(c => !!c.walletId)
      .map(c => ({ value: `c:${c.id}:d`, label: `${c.name} — ${c.function === 'VOUCHER_ALIMENTACAO' ? 'voucher' : 'débito'}` }));
    const groups: OptionGroup[] = [];
    if (credit.length) groups.push({ label: 'Cartão de crédito', options: credit });
    if (debit.length) groups.push({ label: 'Cartão de débito / voucher', options: debit });
    const wallets = this.walletGroups().flatMap(g => g.options);
    if (wallets.length) groups.push({ label: 'Conta (Pix, boleto, débito automático…)', options: wallets });
    return groups;
  });

  ngOnInit(): void {
    this.api.accounts().subscribe({ next: a => this.accounts.set(a), error: e => this.error.set(errorMessage(e)) });
    this.api.cards().subscribe({ next: c => this.cards.set(c), error: e => this.error.set(errorMessage(e)) });
    this.api.categories().subscribe({ next: c => this.categories.set(c), error: e => this.error.set(errorMessage(e)) });
    this.load();
  }

  setMonth(m: string): void {
    this.month.set(m);
    this.load();
  }

  private load(): void {
    this.api.recurrenceMonth(this.month()).subscribe({
      next: d => { this.data.set(d); this.error.set(''); },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  categoryGroups(kind: string): CategoryGroup[] {
    const all = this.categories().filter(c => c.kind === kind && !c.archived);
    return all.filter(c => !c.parentId).map(parent => ({ parent, children: all.filter(c => c.parentId === parent.id) }));
  }

  when(r: Recurrence): string {
    const n = r.intervalCount;
    if (r.frequency === 'SEMANAL') return n > 1 ? `A cada ${n} semanas` : 'Toda semana';
    if (r.frequency === 'ANUAL') {
      const [, m] = r.startDate.split('-');
      return `${n > 1 ? `A cada ${n} anos` : 'Todo ano'} em ${String(r.dayOfMonth).padStart(2, '0')}/${m}`;
    }
    return `${n > 1 ? `A cada ${n} meses` : 'Todo mês'}, dia ${r.dayOfMonth}`;
  }

  isCredit(f: RecForm): boolean {
    return f.kind === 'DESPESA' && f.payWith.startsWith('c:') && f.payWith.endsWith(':c');
  }

  // --- cadastro
  newRec(): void {
    this.formError.set('');
    const today = new Date();
    const iso = today.toISOString().slice(0, 10);
    this.form.set({ id: null, kind: 'DESPESA', description: '', amount: 0, frequency: 'MENSAL', intervalCount: 1,
      dayOfMonth: today.getDate(), startDate: iso, endDate: '', payWith: '', consumesLimit: true, categoryId: null,
      foreign: false, originalCurrency: 'USD', originalAmount: null, notes: '', active: true });
  }

  editRec(r: Recurrence): void {
    this.formError.set('');
    const payWith = r.cardId ? `c:${r.cardId}:${r.credit ? 'c' : 'd'}` : 'w:' + r.walletId;
    this.form.set({ id: r.id, kind: r.kind, description: r.description, amount: r.amount, frequency: r.frequency,
      intervalCount: r.intervalCount, dayOfMonth: r.dayOfMonth, startDate: r.startDate, endDate: r.endDate ?? '',
      payWith, consumesLimit: r.consumesLimit, categoryId: r.categoryId, foreign: !!r.originalCurrency,
      originalCurrency: r.originalCurrency ?? 'USD', originalAmount: r.originalAmount, notes: r.notes ?? '',
      active: r.active });
  }

  setKind(f: RecForm, k: 'RECEITA' | 'DESPESA'): void {
    if (f.kind !== k) f.categoryId = null;
    f.kind = k;
    if (k === 'RECEITA') {
      f.foreign = false;
      if (!f.payWith.startsWith('w:')) f.payWith = '';
    }
  }

  saveRec(f: RecForm): void {
    if (!f.description.trim()) return this.formError.set('Informe a descrição.');
    if (!(Number(f.amount) > 0)) return this.formError.set('Informe o valor previsto.');
    if (!f.payWith) return this.formError.set(f.kind === 'RECEITA' ? 'Escolha a conta.' : 'Escolha como paga.');
    if (f.frequency !== 'SEMANAL' && !(Number(f.dayOfMonth) >= 1 && Number(f.dayOfMonth) <= 31)) return this.formError.set('Dia de 1 a 31.');
    if (f.endDate && f.endDate < f.startDate) return this.formError.set('O fim é antes do início.');
    if (f.foreign && (!/^[A-Za-z]{3}$/.test(f.originalCurrency.trim()) || !(Number(f.originalAmount) > 0))) {
      return this.formError.set('Informe a moeda (ex.: USD) e o valor nela.');
    }
    const [type, id, mode] = f.payWith.split(':');
    const card = type === 'c';
    const req: RecurrenceRequest = {
      kind: f.kind, description: f.description, amount: Number(f.amount), frequency: f.frequency,
      intervalCount: Number(f.intervalCount) || 1, dayOfMonth: f.frequency === 'SEMANAL' ? null : Number(f.dayOfMonth),
      startDate: f.startDate, endDate: f.endDate || null, walletId: card ? null : id, cardId: card ? id : null,
      credit: card ? mode === 'c' : null, consumesLimit: card && mode === 'c' ? f.consumesLimit : null,
      categoryId: f.categoryId, originalCurrency: f.foreign ? f.originalCurrency.trim().toUpperCase() : null,
      originalAmount: f.foreign ? Number(f.originalAmount) : null, notes: f.notes || null, active: f.active,
    };
    this.busy.set(true);
    const call = f.id ? this.api.updateRecurrence(f.id, req) : this.api.createRecurrence(req);
    call.subscribe({
      next: () => { this.busy.set(false); this.form.set(null); this.load(); },
      error: e => { this.busy.set(false); this.formError.set(errorMessage(e)); },
    });
  }

  deleteRec(f: RecForm): void {
    if (!f.id || !confirm(`Excluir a recorrência "${f.description}"? Só é possível se nenhuma ocorrência foi lançada (senão, desative).`)) return;
    this.api.deleteRecurrence(f.id).subscribe({
      next: () => { this.form.set(null); this.load(); },
      error: e => this.formError.set(errorMessage(e)),
    });
  }

  // --- ocorrências
  private recOf(o: Occurrence): Recurrence | undefined {
    return this.data()?.recurrences.find(r => r.id === o.recurrenceId);
  }

  skip(o: Occurrence): void {
    if (!confirm(`Pular "${o.description}" de ${o.date.split('-').reverse().join('/')}?`)) return;
    this.api.skipOccurrence(o.recurrenceId, o.date).subscribe({ next: () => this.load(), error: e => this.error.set(errorMessage(e)) });
  }

  unskip(o: Occurrence): void {
    this.api.unskipOccurrence(o.recurrenceId, o.date).subscribe({ next: () => this.load(), error: e => this.error.set(errorMessage(e)) });
  }

  openLaunch(o: Occurrence): void {
    const rec = this.recOf(o);
    if (!rec) return;
    this.formError.set('');
    const l: LaunchForm = { occ: o, rec, date: o.date, amount: o.expectedAmount, status: 'CONFIRMADO',
      exchangeRate: rec.originalAmount ? Math.round((rec.amount / rec.originalAmount) * 10000) / 10000 : null,
      iof: null, candidates: null };
    this.launch.set(l);
    this.api.occurrenceCandidates(o.recurrenceId, o.date).subscribe({
      next: c => this.launch.update(cur => cur ? { ...cur, candidates: c } : cur),
      error: () => this.launch.update(cur => cur ? { ...cur, candidates: [] } : cur),
    });
  }

  recalcLaunch(l: LaunchForm): void {
    const orig = Number(l.rec.originalAmount), rate = Number(l.exchangeRate);
    if (orig > 0 && rate > 0) l.amount = Math.round((orig * rate + (Number(l.iof) || 0)) * 100) / 100;
  }

  fillIof(l: LaunchForm): void {
    const orig = Number(l.rec.originalAmount), rate = Number(l.exchangeRate);
    if (!(orig > 0 && rate > 0)) return this.formError.set('Informe a cotação.');
    l.iof = Math.round(orig * rate * IOF_RATE * 100) / 100;
    this.recalcLaunch(l);
  }

  link(l: LaunchForm, t: Transaction): void {
    this.api.linkRecurrence(t.id, l.occ.recurrenceId, l.occ.date).subscribe({
      next: () => { this.launch.set(null); this.load(); },
      error: e => this.formError.set(errorMessage(e)),
    });
  }

  saveLaunch(l: LaunchForm): void {
    const amount = Number(l.amount);
    if (!(amount > 0)) return this.formError.set('Informe o valor.');
    const r = l.rec;
    const foreign = !!r.originalCurrency && Number(l.exchangeRate) > 0;
    this.busy.set(true);
    this.api.createTransaction({
      kind: r.kind, status: l.status, date: l.date, description: r.description, amount,
      walletId: r.cardId ? null : r.walletId, targetWalletId: null, categoryId: r.categoryId, notes: r.notes,
      cardId: r.cardId, credit: r.cardId ? r.credit : null, consumesLimit: r.credit ? r.consumesLimit : null,
      originalCurrency: foreign ? r.originalCurrency : null, originalAmount: foreign ? r.originalAmount : null,
      exchangeRate: foreign ? Number(l.exchangeRate) : null, iof: foreign && l.iof != null ? Number(l.iof) : null,
      recurrenceId: r.id, recurrenceDate: l.occ.date,
    }).subscribe({
      next: () => { this.busy.set(false); this.launch.set(null); this.load(); },
      error: e => { this.busy.set(false); this.formError.set(errorMessage(e)); },
    });
  }
}
