import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../core/auth.interceptor';
import { BrlPipe, DateBrPipe } from '../core/format';
import {
  BankAccount, Card, CardFormat, CardFunction, CreditLine, FORMAT_LABEL, FUNCTION_LABEL, INVOICE_STATUS_LABEL,
} from '../core/models';
import { LimitMeterComponent } from '../shared/limit-meter.component';

interface LineForm {
  id: string | null;
  name: string;
  color: string;
  limit: number;
  closingDay: number;
  dueDay: number;
  paymentWalletId: string | null;
  archived: boolean;
  cards: { name: string; format: CardFormat; walletId: string | null }[];
}

interface CardForm {
  id: string | null;
  name: string;
  format: CardFormat;
  function: CardFunction;
  creditLineId: string | null;
  walletId: string | null;
  archived: boolean;
}

interface WalletOption { id: string; label: string; }

/**
 * Cartões. Cartão de crédito = um limite compartilhado pelos cartões físico e digitais dele, com fechamento e
 * vencimento. Cartões de débito e voucher debitam direto de uma carteira.
 */
@Component({
  selector: 'app-cards',
  imports: [FormsModule, RouterLink, BrlPipe, DateBrPipe, LimitMeterComponent],
  template: `
    <div class="page-head">
      <h1>Cartões</h1>
      <label class="check"><input type="checkbox" [(ngModel)]="showArchived"> Mostrar arquivados</label>
      <button class="btn ghost" type="button" (click)="newCard(null)">Novo débito / voucher</button>
      <button class="btn" type="button" (click)="newLine()">Novo cartão de crédito</button>
      <p class="sub">Cartões do mesmo banco dividem o limite (ex.: Nubank físico e virtual). Compras no crédito vão para a
        fatura; o saldo da conta só muda quando você paga a fatura.</p>
    </div>
    @if (error()) { <div class="error" role="alert">{{ error() }}</div> }

    @if (activeLines().length > 0) {
      <div class="kpis">
        <div class="kpi main">
          <div class="label">Faturas a pagar</div>
          <div class="value">{{ toPay() | brl }}</div>
          <div class="detail">fechadas não pagas + abertas</div>
        </div>
        <div class="kpi">
          <div class="label">Limite total</div>
          <div class="value">{{ totals().limit | brl }}</div>
        </div>
        <div class="kpi">
          <div class="label">Limite disponível</div>
          <div class="value" [class.neg]="totals().available < 0">{{ totals().available | brl }}</div>
          <div class="detail">usado {{ totals().used | brl }} (com parcelas futuras)</div>
        </div>
      </div>
    }

    @if (loaded() && visibleLines().length === 0 && visibleDebit().length === 0) {
      <div class="card empty">Nenhum cartão ainda. Cadastre seus cartões de crédito (Nubank, Itaú…) e os de débito e
        voucher (Wiipo VA/VR…).</div>
    }

    <div class="list">
      @for (l of visibleLines(); track l.id) {
        <section class="card line" [class.archived]="l.archived" [style.border-left-color]="l.color || 'var(--brand)'">
          <div class="line-head">
            <div class="title">
              <h2>{{ l.name }}</h2>
              <span class="tag">Fecha dia {{ l.closingDay }} · vence dia {{ l.dueDay }}</span>
              @if (l.archived) { <span class="tag warn">Arquivado</span> }
            </div>
            <div class="head-actions">
              <a class="btn small" [routerLink]="['/cartoes', l.id, 'faturas']">Faturas</a>
              <button class="btn ghost small" type="button" (click)="editLine(l)">Editar</button>
            </div>
          </div>

          <app-limit-meter [used]="l.used" [limit]="l.limit" [available]="l.available" />
          <p class="muted small">Limite {{ l.limit | brl }}@if (l.reserved > 0) { · reservado por recorrências {{ l.reserved | brl }} }</p>

          <div class="invoices">
            @if (l.dueInvoice; as inv) {
              <a class="inv" [routerLink]="['/cartoes', l.id, 'faturas']" [queryParams]="{ fatura: inv.id }">
                <span class="muted">Fatura fechada · vence {{ inv.dueDate | dateBr }}</span>
                <strong>{{ inv.outstanding | brl }}</strong>
                <span class="tag" [class.danger]="inv.status === 'VENCIDA'" [class.warn]="inv.status === 'FECHADA'">{{ statusLabel[inv.status] }}</span>
              </a>
            }
            @if (l.currentInvoice; as inv) {
              <a class="inv" [routerLink]="['/cartoes', l.id, 'faturas']" [queryParams]="{ fatura: inv.id }">
                <span class="muted">Fatura atual · fecha {{ inv.closingDate | dateBr }}</span>
                <strong>{{ inv.total | brl }}</strong>
                <span class="tag accent">Aberta</span>
              </a>
            }
          </div>

          <div class="table-wrap">
            <table class="data">
              <thead><tr><th>Cartão</th><th>Formato</th><th class="wide">Débito em</th><th></th></tr></thead>
              <tbody>
                @for (c of cardsOf(l); track c.id) {
                  <tr [class.muted]="c.archived">
                    <td>{{ c.name }} @if (c.archived) { <span class="tag warn">arquivado</span> }</td>
                    <td>{{ formatLabel[c.format] }}</td>
                    <td class="wide muted">{{ c.walletId ? c.accountName + ' · ' + c.walletName : 'Só crédito' }}</td>
                    <td class="actions">
                      <a class="btn ghost small" routerLink="/lancamentos" [queryParams]="{ novo: c.id }">Lançar compra</a>
                      <button class="btn ghost small" type="button" (click)="editCard(c)">Editar</button>
                    </td>
                  </tr>
                } @empty {
                  <tr><td colspan="4" class="empty">Nenhum cartão neste limite.</td></tr>
                }
              </tbody>
            </table>
          </div>
          <button class="btn ghost small add" type="button" (click)="newCard(l)">+ Cartão neste limite</button>
        </section>
      }

      @if (visibleDebit().length > 0) {
        <section class="card">
          <h2>Débito e voucher</h2>
          <div class="table-wrap">
            <table class="data">
              <thead><tr><th>Cartão</th><th class="wide">Tipo</th><th>Debita de</th><th></th></tr></thead>
              <tbody>
                @for (c of visibleDebit(); track c.id) {
                  <tr [class.muted]="c.archived">
                    <td>{{ c.name }} <span class="muted small">· {{ formatLabel[c.format] }}</span>
                      @if (c.archived) { <span class="tag warn">arquivado</span> }</td>
                    <td class="wide">{{ functionLabel[c.function] }}</td>
                    <td class="muted">{{ c.accountName }} · {{ c.walletName }}</td>
                    <td class="actions">
                      <a class="btn ghost small" routerLink="/lancamentos" [queryParams]="{ novo: c.id }">Lançar compra</a>
                      <button class="btn ghost small" type="button" (click)="editCard(c)">Editar</button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }
    </div>

    <!-- cartão de crédito (limite) -->
    @if (lineForm(); as f) {
      <div class="backdrop" (click)="lineForm.set(null)">
        <form class="card modal wide" (click)="$event.stopPropagation()" (ngSubmit)="saveLine(f)">
          <h2>{{ f.id ? 'Editar cartão de crédito' : 'Novo cartão de crédito' }}</h2>
          @if (formError()) { <div class="error">{{ formError() }}</div> }
          <div class="row">
            <div class="field" style="flex: 2">
              <label for="ln">Nome</label>
              <input id="ln" name="ln" [(ngModel)]="f.name" maxlength="60" required placeholder="Ex.: Nubank, Itaú, Nubank PJ">
            </div>
            <div class="field">
              <label for="lc">Cor</label>
              <input id="lc" name="lc" type="color" [(ngModel)]="f.color" style="height: 38px; padding: 2px">
            </div>
          </div>
          <div class="row">
            <div class="field">
              <label for="ll">Limite</label>
              <span class="money"><input id="ll" name="ll" type="number" step="0.01" min="0" [(ngModel)]="f.limit" required></span>
            </div>
            <div class="field">
              <label for="lf">Dia do fechamento</label>
              <input id="lf" name="lf" type="number" min="1" max="31" [(ngModel)]="f.closingDay" required>
            </div>
            <div class="field">
              <label for="lv">Dia do vencimento</label>
              <input id="lv" name="lv" type="number" min="1" max="31" [(ngModel)]="f.dueDay" required>
            </div>
          </div>
          <p class="hint" style="margin-top: -6px">Compras no dia do fechamento ou depois entram na fatura seguinte.</p>
          <div class="field">
            <label for="lw">Pagar a fatura com (sugestão)</label>
            <select id="lw" name="lw" [(ngModel)]="f.paymentWalletId">
              <option [ngValue]="null">Escolher na hora</option>
              @for (w of walletOptions(); track w.id) { <option [ngValue]="w.id">{{ w.label }}</option> }
            </select>
          </div>
          @if (!f.id) {
            <h3>Cartões deste limite</h3>
            <p class="hint" style="margin: -4px 0 8px">Ex.: "Nubank físico" e "Nubank virtual". A carteira é opcional e
              serve para compras no débito.</p>
            @for (c of f.cards; track $index; let i = $index) {
              <div class="card-row">
                <input [name]="'cn' + i" [(ngModel)]="c.name" maxlength="60" required placeholder="Nome do cartão" aria-label="Nome do cartão">
                <select [name]="'cf' + i" [(ngModel)]="c.format" aria-label="Formato">
                  <option value="FISICO">Físico</option>
                  <option value="DIGITAL">Digital</option>
                </select>
                <select [name]="'cw' + i" [(ngModel)]="c.walletId" aria-label="Carteira do débito">
                  <option [ngValue]="null">Sem débito</option>
                  @for (w of walletOptions(); track w.id) { <option [ngValue]="w.id">Débito: {{ w.label }}</option> }
                </select>
                <button type="button" class="icon-btn" (click)="f.cards.splice(i, 1)" [disabled]="f.cards.length === 1" aria-label="Remover cartão">✕</button>
              </div>
            }
            <button type="button" class="btn ghost small" (click)="f.cards.push({ name: '', format: 'DIGITAL', walletId: null })">+ Cartão</button>
          } @else {
            <label class="check" style="margin-top: 4px"><input type="checkbox" name="la" [(ngModel)]="f.archived"> Arquivado (some do painel, mantém o histórico)</label>
          }
          <div class="form-actions">
            @if (f.id) { <button class="btn danger" type="button" (click)="deleteLine(f)">Excluir</button> }
            <span class="spacer"></span>
            <button class="btn ghost" type="button" (click)="lineForm.set(null)">Cancelar</button>
            <button class="btn" type="submit" [disabled]="busy()">Salvar</button>
          </div>
        </form>
      </div>
    }

    <!-- cartão -->
    @if (cardForm(); as f) {
      <div class="backdrop" (click)="cardForm.set(null)">
        <form class="card modal" (click)="$event.stopPropagation()" (ngSubmit)="saveCard(f)">
          <h2>{{ f.id ? 'Editar cartão' : 'Novo cartão' }}</h2>
          @if (formError()) { <div class="error">{{ formError() }}</div> }
          <div class="field">
            <label for="cn">Nome</label>
            <input id="cn" name="cn" [(ngModel)]="f.name" maxlength="60" required placeholder="Ex.: Nubank virtual, Wiipo VA">
          </div>
          <div class="row">
            <div class="field">
              <label for="cf">Formato</label>
              <select id="cf" name="cf" [(ngModel)]="f.format">
                <option value="FISICO">Físico</option>
                <option value="DIGITAL">Digital</option>
              </select>
            </div>
            <div class="field">
              <label for="cu">Função</label>
              <select id="cu" name="cu" [(ngModel)]="f.function">
                <option value="CREDITO_DEBITO">Crédito e débito</option>
                <option value="SO_DEBITO">Só débito</option>
                <option value="VOUCHER_ALIMENTACAO">Voucher alimentação</option>
              </select>
            </div>
          </div>
          @if (f.function === 'CREDITO_DEBITO') {
            <div class="field">
              <label for="cl">Limite (cartão de crédito)</label>
              <select id="cl" name="cl" [(ngModel)]="f.creditLineId" required>
                <option [ngValue]="null" disabled>Escolha…</option>
                @for (l of activeLines(); track l.id) { <option [ngValue]="l.id">{{ l.name }}</option> }
              </select>
              @if (activeLines().length === 0) { <p class="hint">Cadastre antes um cartão de crédito (limite).</p> }
            </div>
          }
          <div class="field">
            <label for="cw">{{ f.function === 'CREDITO_DEBITO' ? 'Débito em (opcional)' : 'Debita de' }}</label>
            <select id="cw" name="cw" [(ngModel)]="f.walletId">
              <option [ngValue]="null" [disabled]="f.function !== 'CREDITO_DEBITO'">{{ f.function === 'CREDITO_DEBITO' ? 'Sem débito (só crédito)' : 'Escolha…' }}</option>
              @for (w of walletOptions(); track w.id) { <option [ngValue]="w.id">{{ w.label }}</option> }
            </select>
            @if (f.function === 'VOUCHER_ALIMENTACAO') { <p class="hint">Ex.: carteira "VA/VR" da Wiipo.</p> }
          </div>
          @if (f.id) {
            <label class="check"><input type="checkbox" name="ca" [(ngModel)]="f.archived"> Arquivado</label>
          }
          <div class="form-actions">
            @if (f.id) { <button class="btn danger" type="button" (click)="deleteCard(f)">Excluir</button> }
            <span class="spacer"></span>
            <button class="btn ghost" type="button" (click)="cardForm.set(null)">Cancelar</button>
            <button class="btn" type="submit" [disabled]="busy()">Salvar</button>
          </div>
        </form>
      </div>
    }
  `,
  styles: `
    .list { display: grid; gap: 14px; }
    .line { border-left: 4px solid var(--brand); }
    .line.archived { opacity: .7; }
    .line-head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
    .title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; flex: 1; min-width: 200px; }
    .title h2 { margin: 0; }
    .head-actions { display: flex; gap: 6px; }
    .small { font-size: 12px; }
    p.small { margin: 4px 0 0; }
    .invoices { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px; margin: 12px 0; }
    .inv { display: grid; grid-template-columns: 1fr auto; gap: 2px 8px; align-items: center; padding: 10px 12px;
      border: 1px solid var(--border); border-radius: 10px; text-decoration: none; color: var(--text); }
    .inv:hover { background: var(--surface-2); }
    .inv .muted { grid-column: 1 / -1; font-size: 12px; }
    .inv strong { font-size: 17px; font-variant-numeric: tabular-nums; }
    .add { margin-top: 10px; }
    .card-row { display: grid; grid-template-columns: 1fr 110px 1fr 34px; gap: 8px; margin-bottom: 8px; }
    tr.muted td { color: var(--muted); }
    @media (max-width: 700px) {
      .wide { display: none; }
      .card-row { grid-template-columns: 1fr 34px; }
      .card-row select { grid-column: 1; }
    }
  `,
})
export class CardsComponent implements OnInit {
  private api = inject(ApiService);

  readonly formatLabel = FORMAT_LABEL;
  readonly functionLabel = FUNCTION_LABEL;
  readonly statusLabel = INVOICE_STATUS_LABEL;

  lines = signal<CreditLine[]>([]);
  cards = signal<Card[]>([]);
  accounts = signal<BankAccount[]>([]);
  loaded = signal(false);
  showArchived = false;
  error = signal('');
  formError = signal('');
  busy = signal(false);
  lineForm = signal<LineForm | null>(null);
  cardForm = signal<CardForm | null>(null);

  activeLines = computed(() => this.lines().filter(l => !l.archived));
  toPay = computed(() => this.activeLines().reduce((s, l) => s + l.toPay, 0));
  totals = computed(() => this.activeLines().reduce(
    (t, l) => ({ limit: t.limit + l.limit, used: t.used + l.used, available: t.available + l.available }),
    { limit: 0, used: 0, available: 0 }));

  walletOptions = computed<WalletOption[]>(() => this.accounts().filter(a => !a.archived).flatMap(a =>
    a.wallets.filter(w => !w.archived).map(w => ({ id: w.id, label: a.wallets.length > 1 ? `${a.name} · ${w.name}` : a.name }))));

  ngOnInit(): void {
    this.load();
    this.api.accounts().subscribe({ next: a => this.accounts.set(a), error: e => this.error.set(errorMessage(e)) });
  }

  visibleLines(): CreditLine[] {
    return this.lines().filter(l => this.showArchived || !l.archived);
  }

  cardsOf(l: CreditLine): Card[] {
    return l.cards.filter(c => this.showArchived || !c.archived);
  }

  visibleDebit(): Card[] {
    return this.cards().filter(c => c.function !== 'CREDITO_DEBITO' && (this.showArchived || !c.archived));
  }

  private load(): void {
    this.api.creditLines().subscribe({
      next: list => { this.lines.set(list); this.loaded.set(true); },
      error: e => this.error.set(errorMessage(e)),
    });
    this.api.cards().subscribe({ next: c => this.cards.set(c), error: e => this.error.set(errorMessage(e)) });
  }

  private done(): void {
    this.busy.set(false);
    this.lineForm.set(null);
    this.cardForm.set(null);
    this.load();
  }

  private fail(e: unknown): void {
    this.busy.set(false);
    this.formError.set(errorMessage(e));
  }

  // --- cartão de crédito (limite)
  newLine(): void {
    this.formError.set('');
    this.lineForm.set({ id: null, name: '', color: '#0F5F56', limit: 0, closingDay: 3, dueDay: 10,
      paymentWalletId: null, archived: false, cards: [{ name: '', format: 'FISICO', walletId: null }] });
  }

  editLine(l: CreditLine): void {
    this.formError.set('');
    this.lineForm.set({ id: l.id, name: l.name, color: l.color ?? '#0F5F56', limit: l.limit, closingDay: l.closingDay,
      dueDay: l.dueDay, paymentWalletId: l.paymentWalletId, archived: l.archived, cards: [] });
  }

  saveLine(f: LineForm): void {
    const closing = Number(f.closingDay), due = Number(f.dueDay);
    if (!f.name.trim()) return this.formError.set('Informe o nome do cartão de crédito.');
    if (!(closing >= 1 && closing <= 31) || !(due >= 1 && due <= 31)) return this.formError.set('Os dias vão de 1 a 31.');
    if (closing === due) return this.formError.set('O fechamento e o vencimento precisam ser em dias diferentes.');
    if (!f.id && f.cards.some(c => !c.name.trim())) return this.formError.set('Dê um nome a cada cartão.');
    this.busy.set(true);
    const req = { name: f.name, color: f.color, limit: Number(f.limit) || 0, closingDay: closing, dueDay: due,
      paymentWalletId: f.paymentWalletId, archived: f.archived, cards: f.id ? undefined : f.cards };
    const call = f.id ? this.api.updateCreditLine(f.id, req) : this.api.createCreditLine(req);
    call.subscribe({ next: () => this.done(), error: e => this.fail(e) });
  }

  deleteLine(f: LineForm): void {
    if (!f.id || !confirm(`Excluir o cartão de crédito ${f.name} e seus cartões? Só é possível sem compras lançadas.`)) return;
    this.api.deleteCreditLine(f.id).subscribe({ next: () => this.done(), error: e => this.fail(e) });
  }

  // --- cartão
  newCard(l: CreditLine | null): void {
    this.formError.set('');
    this.cardForm.set({ id: null, name: '', format: l ? 'DIGITAL' : 'FISICO',
      function: l ? 'CREDITO_DEBITO' : 'SO_DEBITO', creditLineId: l?.id ?? null, walletId: null, archived: false });
  }

  editCard(c: Card): void {
    this.formError.set('');
    this.cardForm.set({ id: c.id, name: c.name, format: c.format, function: c.function, creditLineId: c.creditLineId,
      walletId: c.walletId, archived: c.archived });
  }

  saveCard(f: CardForm): void {
    if (!f.name.trim()) return this.formError.set('Informe o nome do cartão.');
    if (f.function === 'CREDITO_DEBITO' && !f.creditLineId) return this.formError.set('Escolha o limite do cartão.');
    if (f.function !== 'CREDITO_DEBITO' && !f.walletId) return this.formError.set('Escolha a carteira de onde sai o dinheiro.');
    this.busy.set(true);
    const req = { name: f.name, format: f.format, function: f.function,
      creditLineId: f.function === 'CREDITO_DEBITO' ? f.creditLineId : null, walletId: f.walletId, archived: f.archived };
    const call = f.id ? this.api.updateCard(f.id, req) : this.api.createCard(req);
    call.subscribe({ next: () => this.done(), error: e => this.fail(e) });
  }

  deleteCard(f: CardForm): void {
    if (!f.id || !confirm(`Excluir o cartão ${f.name}? Só é possível sem lançamentos.`)) return;
    this.api.deleteCard(f.id).subscribe({ next: () => this.done(), error: e => this.fail(e) });
  }
}
