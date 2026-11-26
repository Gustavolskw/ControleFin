import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../core/auth.interceptor';
import { BrlPipe, DateBrPipe, isoDate } from '../core/format';
import {
  BankAccount, INDEXER_LABEL, INVESTMENT_TYPES, INVESTMENT_TYPE_LABEL, Indexer, Investment, InvestmentDetail,
  InvestmentRequest, InvestmentType, MOVEMENT_LABEL, Movement, MovementKind, MovementRequest, Portfolio,
} from '../core/models';

interface InvForm {
  id: string | null;
  type: InvestmentType;
  name: string;
  ticker: string;
  indexer: Indexer;
  rate: number | null;
  maturityDate: string;
  accountId: string | null;
  walletId: string | null;
  notes: string;
  archived: boolean;
  /** Só ao criar: posição que já existe (não mexe nas contas). */
  hasPosition: boolean;
  positionAmount: number | null;
  positionQuantity: number | null;
  positionPrice: number | null;
  positionDate: string;
}

interface MovForm {
  id: string | null;
  kind: MovementKind;
  date: string;
  amount: number | null;
  quantity: number | null;
  unitPrice: number | null;
  fees: number | null;
  walletId: string;
  notes: string;
  duplicate: string;
}

interface Option { value: string; label: string; }

const qtyFmt = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 8 });
const priceFmt = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL', maximumFractionDigits: 2 });
/** Preços abaixo de R$ 1 (cripto fracionada, cotas baratas) com mais casas. */
const smallPriceFmt = new Intl.NumberFormat('pt-BR', { style: 'currency', currency: 'BRL', maximumFractionDigits: 8 });
const pctFmt = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 2, minimumFractionDigits: 2 });

/**
 * Investimentos: caixinhas, CDB, Tesouro (por valor, rendendo pelo CDI/IPCA do Banco Central) e ações, FIIs, cripto
 * (por quantidade e preço). Ficam fora das carteiras: aporte sai da conta, resgate volta, proventos entram como receita.
 */
@Component({
  selector: 'app-investments',
  imports: [FormsModule, BrlPipe, DateBrPipe],
  template: `
    <div class="page-head">
      <h1>Investimentos</h1>
      <button class="btn" type="button" (click)="newInv()">Novo investimento</button>
      <p class="sub">Caixinhas, CDB, Tesouro, ações, FIIs e cripto. A renda fixa rende sozinha pelo CDI/IPCA do Banco Central;
        ações, FIIs e cripto você atualiza a cotação.</p>
    </div>
    @if (error()) { <div class="error" role="alert">{{ error() }}</div> }

    @if (data(); as p) {
      <div class="kpis">
        <div class="kpi main">
          <div class="label">Valor atual</div>
          <div class="value">{{ p.currentValue | brl }}</div>
          <div class="detail">@if (p.ratesUntil) { rendimento até {{ p.ratesUntil | dateBr }} } @else { sem taxas do Banco Central ainda }</div>
        </div>
        <div class="kpi">
          <div class="label">Investido</div>
          <div class="value">{{ p.invested | brl }}</div>
          <div class="detail">aportes − resgates · custo das ações</div>
        </div>
        <div class="kpi">
          <div class="label">Rendimento</div>
          <div class="value" [class.pos]="p.gain > 0" [class.neg]="p.gain < 0">{{ p.gain | brl }}</div>
          <div class="detail">{{ pct(p.gain, p.invested) }} sobre o investido</div>
        </div>
        <div class="kpi">
          <div class="label">Proventos recebidos</div>
          <div class="value pos">{{ p.income | brl }}</div>
          <div class="detail">lucro realizado em vendas: {{ p.realizedGain | brl }}</div>
        </div>
      </div>

      @if (p.byType.length > 0) {
        <section class="card alloc">
          <h2>Distribuição</h2>
          <div class="split">
            <span>Renda fixa <strong>{{ p.fixedIncome | brl }}</strong></span>
            <span>Renda variável <strong>{{ p.variableIncome | brl }}</strong></span>
          </div>
          <ul class="bars" role="list">
            @for (t of p.byType; track t.type) {
              <li [title]="typeLabel[t.type] + ': ' + (t.value | brl)">
                <div class="bar-label"><span>{{ typeLabel[t.type] }}</span><span class="val">{{ t.value | brl }} · {{ pct(t.value, p.currentValue) }}</span></div>
                <div class="track"><div class="fill" [style.width.%]="share(t.value, p)"></div></div>
              </li>
            }
          </ul>
        </section>
      }

      <div class="table-wrap">
        <table class="data">
          <thead><tr><th>Investimento</th><th class="wide">Instituição</th><th class="r wide">Investido</th><th class="r">Valor atual</th><th class="r wide">Rendimento</th><th class="wide"></th></tr></thead>
          <tbody>
            @for (i of p.investments; track i.id) {
              <tr class="clickable" [class.dim]="i.archived" (click)="open(i)">
                <td>
                  <strong>{{ i.name }}</strong>
                  <span class="tag">{{ typeLabel[i.type] }}</span>
                  @if (i.ticker && i.ticker !== i.name) { <span class="tag">{{ i.ticker }}</span> }
                  @if (i.archived) { <span class="tag">Arquivado</span> }
                  <div class="meta muted">{{ how(i) }}@if (i.accountName) { · {{ i.accountName }} }</div>
                </td>
                <td class="muted wide">{{ i.accountName ?? '—' }}</td>
                <td class="money-cell wide">{{ i.invested | brl }}</td>
                <td class="money-cell"><strong>{{ i.currentValue | brl }}</strong>
                  <div class="muted small mob" [class.pos]="i.gain > 0" [class.neg]="i.gain < 0">{{ i.gain >= 0 ? '+' : '' }}{{ i.gain | brl }}</div></td>
                <td class="money-cell wide" [class.pos]="i.gain > 0" [class.neg]="i.gain < 0">
                  {{ i.gain >= 0 ? '+' : '' }}{{ i.gain | brl }}
                  @if (i.gainPct != null) { <div class="small">{{ fmtPct(i.gainPct) }}</div> }
                </td>
                <td class="actions wide" (click)="$event.stopPropagation()">
                  @if (!i.archived) { <button class="btn small" type="button" (click)="newMov(i)">Movimentar</button> }
                  <button class="btn ghost small" type="button" (click)="open(i)">Abrir</button>
                </td>
              </tr>
            } @empty {
              <tr><td colspan="6" class="empty">Nenhum investimento. Cadastre sua caixinha, CDB, Tesouro, ações ou FIIs em "Novo investimento".</td></tr>
            }
          </tbody>
        </table>
      </div>
    } @else if (!error()) {
      <p class="muted">Carregando…</p>
    }

    <!-- detalhe -->
    @if (detail(); as d) {
      <div class="backdrop" (click)="closeDetail()">
        <div class="card modal wide" (click)="$event.stopPropagation()" role="dialog" aria-label="Investimento">
          <div class="detail-head">
            <div>
              <h2>{{ d.investment.name }}</h2>
              <p class="hint">{{ typeLabel[d.investment.type] }} · {{ how(d.investment) }}
                @if (d.investment.accountName) { · {{ d.investment.accountName }} }
                @if (d.investment.maturityDate) { · vence {{ d.investment.maturityDate | dateBr }} }</p>
            </div>
            <button class="icon-btn" type="button" (click)="closeDetail()" aria-label="Fechar">✕</button>
          </div>
          @if (detailError()) { <div class="error">{{ detailError() }}</div> }
          <div class="kpis compact">
            <div class="kpi"><div class="label">Valor atual</div><div class="value">{{ d.investment.currentValue | brl }}</div>
              <div class="detail">{{ valueNote(d.investment) }}</div></div>
            <div class="kpi"><div class="label">{{ d.investment.byQuantity ? 'Custo da posição' : 'Investido' }}</div><div class="value">{{ d.investment.invested | brl }}</div></div>
            <div class="kpi"><div class="label">Rendimento</div>
              <div class="value" [class.pos]="d.investment.gain > 0" [class.neg]="d.investment.gain < 0">{{ d.investment.gain | brl }}</div>
              @if (d.investment.gainPct != null) { <div class="detail">{{ fmtPct(d.investment.gainPct) }}</div> }</div>
            @if (d.investment.byQuantity) {
              <div class="kpi"><div class="label">Quantidade</div><div class="value">{{ qty(d.investment.quantity) }}</div>
                <div class="detail">preço médio {{ price(d.investment.averagePrice) }}</div></div>
            }
            @if (d.investment.income > 0 || d.investment.realizedGain) {
              <div class="kpi"><div class="label">Proventos</div><div class="value pos">{{ d.investment.income | brl }}</div>
                @if (d.investment.realizedGain) { <div class="detail">lucro realizado {{ d.investment.realizedGain | brl }}</div> }</div>
            }
          </div>

          <div class="form-actions" style="margin: 0 0 12px">
            <button class="btn ghost" type="button" (click)="editInv(d.investment)">Editar investimento</button>
            <span class="spacer"></span>
            @if (!d.investment.archived) { <button class="btn" type="button" (click)="newMov(d.investment)">Novo movimento</button> }
          </div>

          <div class="table-wrap">
            <table class="data">
              <thead><tr><th class="wide">Data</th><th>Movimento</th><th class="r">Valor</th><th class="wide">Conta</th><th></th></tr></thead>
              <tbody>
                @for (m of d.movements; track m.id) {
                  <tr>
                    <td class="wide">{{ m.date | dateBr }}</td>
                    <td>{{ movLabel[m.kind] }}
                      @if (m.quantity != null) { <span class="muted small">{{ qty(m.quantity) }} × {{ price(m.unitPrice) }}</span> }
                      @if (m.kind === 'ATUALIZACAO' && m.unitPrice != null) { <span class="muted small">cotação {{ price(m.unitPrice) }}</span> }
                      @if (m.origin === 'API') { <span class="tag accent">Claude</span> }
                      @if (m.notes) { <div class="muted small">{{ m.notes }}</div> }
                      <div class="meta muted">{{ m.date | dateBr }} · {{ m.walletName ?? 'sem movimentar conta' }}</div>
                    </td>
                    <td class="money-cell" [class.pos]="m.kind === 'PROVENTO'">{{ m.amount != null ? (m.amount | brl) : '—' }}</td>
                    <td class="muted wide">{{ m.walletName ?? 'sem movimentar conta' }}</td>
                    <td class="actions"><button class="btn ghost small" type="button" (click)="editMov(d.investment, m)">Editar</button></td>
                  </tr>
                } @empty {
                  <tr><td colspan="5" class="empty">Nenhum movimento. Registre o primeiro aporte ou compra.</td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      </div>
    }

    <!-- cadastro -->
    @if (form(); as f) {
      <div class="backdrop" (click)="form.set(null)">
        <form class="card modal wide" (click)="$event.stopPropagation()" (ngSubmit)="saveInv(f)">
          <h2>{{ f.id ? 'Editar investimento' : 'Novo investimento' }}</h2>
          @if (formError()) { <div class="error">{{ formError() }}</div> }
          <div class="row">
            <div class="field">
              <label for="it">Tipo</label>
              <select id="it" name="it" [(ngModel)]="f.type" (ngModelChange)="setType(f, $event)">
                @for (t of types; track t.key) { <option [value]="t.key">{{ t.label }}</option> }
              </select>
            </div>
            <div class="field" style="flex: 2">
              <label for="in">Nome</label>
              <input id="in" name="in" [(ngModel)]="f.name" maxlength="80" required [placeholder]="namePlaceholder(f)">
            </div>
          </div>
          @if (byQty(f)) {
            <div class="field">
              <label for="ik">Código (ticker)</label>
              <input id="ik" name="ik" [(ngModel)]="f.ticker" maxlength="20" placeholder="Ex.: PETR4, MXRF11, BTC" style="text-transform: uppercase">
            </div>
          } @else {
            <div class="row">
              <div class="field">
                <label for="ix">Rende</label>
                <select id="ix" name="ix" [(ngModel)]="f.indexer" (ngModelChange)="setIndexer(f, $event)">
                  @for (x of indexers; track x) { <option [value]="x">{{ indexerLabel[x] }}</option> }
                </select>
              </div>
              @if (f.indexer !== 'POUPANCA' && f.indexer !== 'MANUAL') {
                <div class="field">
                  <label for="ir">{{ rateLabel(f.indexer) }}</label>
                  <input id="ir" name="ir" type="number" step="0.01" [(ngModel)]="f.rate" required>
                </div>
              }
              <div class="field">
                <label for="iv">Vencimento (opcional)</label>
                <input id="iv" name="iv" type="date" [(ngModel)]="f.maturityDate">
              </div>
            </div>
            <p class="hint" style="margin: -6px 0 12px">{{ indexerHint(f.indexer) }}</p>
          }
          <div class="row">
            <div class="field">
              <label for="ia">Instituição</label>
              <select id="ia" name="ia" [(ngModel)]="f.accountId">
                <option [ngValue]="null">—</option>
                @for (a of activeAccounts(); track a.id) { <option [value]="a.id">{{ a.name }}</option> }
              </select>
            </div>
            <div class="field">
              <label for="iw">Conta padrão para aportes e resgates</label>
              <select id="iw" name="iw" [(ngModel)]="f.walletId">
                <option [ngValue]="null">—</option>
                @for (o of walletOptions(); track o.value) { <option [value]="o.value">{{ o.label }}</option> }
              </select>
            </div>
          </div>
          @if (!f.id) {
            <label class="check"><input type="checkbox" name="ip" [(ngModel)]="f.hasPosition"> Já tenho este investimento (registrar o que já existe)</label>
            @if (f.hasPosition) {
              <div class="row">
                @if (byQty(f)) {
                  <div class="field"><label for="pq">Quantidade</label><input id="pq" name="pq" type="number" step="any" min="0" [(ngModel)]="f.positionQuantity"></div>
                  <div class="field"><label for="pp">Preço médio</label><span class="money"><input id="pp" name="pp" type="number" step="any" min="0" [(ngModel)]="f.positionPrice"></span></div>
                } @else {
                  <div class="field"><label for="pa">Saldo atual</label><span class="money"><input id="pa" name="pa" type="number" step="0.01" min="0" [(ngModel)]="f.positionAmount"></span></div>
                }
                <div class="field"><label for="pd">Data</label><input id="pd" name="pd" type="date" [(ngModel)]="f.positionDate"></div>
              </div>
              <p class="hint" style="margin: -6px 0 12px">Entra como aporte/compra <strong>sem tirar dinheiro das contas</strong> (o dinheiro já estava investido).</p>
            }
          }
          <div class="field">
            <label for="io">Observações</label>
            <textarea id="io" name="io" [(ngModel)]="f.notes" maxlength="1000" rows="2"></textarea>
          </div>
          @if (f.id) {
            <label class="check"><input type="checkbox" name="iz" [(ngModel)]="f.archived"> Arquivado (some dos totais, mantém o histórico)</label>
          }
          <div class="form-actions">
            @if (f.id) { <button class="btn danger" type="button" (click)="deleteInv(f)">Excluir</button> }
            <span class="spacer"></span>
            <button class="btn ghost" type="button" (click)="form.set(null)">Cancelar</button>
            <button class="btn" type="submit" [disabled]="busy()">Salvar</button>
          </div>
        </form>
      </div>
    }

    <!-- movimento -->
    @if (mov(); as m) {
      <div class="backdrop" (click)="mov.set(null)">
        <form class="card modal" (click)="$event.stopPropagation()" (ngSubmit)="saveMov(m, false)">
          <h2>{{ m.id ? 'Editar movimento' : 'Novo movimento' }} — {{ movInv()?.name }}</h2>
          @if (formError()) { <div class="error">{{ formError() }}</div> }
          @if (m.duplicate) {
            <div class="warning">{{ m.duplicate }}
              <div style="margin-top: 6px"><button class="btn small" type="button" (click)="saveMov(m, true)">Gravar mesmo assim</button></div>
            </div>
          }
          <div class="field">
            <div class="segmented" role="group" aria-label="Movimento">
              @for (k of movKinds(); track k) {
                <button type="button" [class.active]="m.kind === k" (click)="setMovKind(m, k)">{{ movButton(k) }}</button>
              }
            </div>
            <p class="hint">{{ movHint(m.kind) }}</p>
          </div>
          <div class="row">
            <div class="field">
              <label for="md">Data</label>
              <input id="md" name="md" type="date" [(ngModel)]="m.date" [max]="today" required>
            </div>
            @if (m.kind === 'COMPRA' || m.kind === 'VENDA') {
              <div class="field"><label for="mq">Quantidade</label><input id="mq" name="mq" type="number" step="any" min="0" [(ngModel)]="m.quantity" required></div>
            }
          </div>
          @if (m.kind === 'COMPRA' || m.kind === 'VENDA') {
            <div class="row">
              <div class="field"><label for="mp">Preço unitário</label><span class="money"><input id="mp" name="mp" type="number" step="any" min="0" [(ngModel)]="m.unitPrice" required></span></div>
              <div class="field"><label for="mf">Taxas</label><span class="money"><input id="mf" name="mf" type="number" step="0.01" min="0" [(ngModel)]="m.fees"></span></div>
            </div>
            <p class="hint" style="margin: -6px 0 12px">Total: <strong>{{ tradeTotal(m) | brl }}</strong></p>
          } @else if (m.kind === 'ATUALIZACAO' && movInv()?.byQuantity) {
            <div class="field"><label for="mc">Cotação atual (preço unitário)</label><span class="money"><input id="mc" name="mc" type="number" step="any" min="0" [(ngModel)]="m.unitPrice" required></span></div>
          } @else {
            <div class="field">
              <label for="ma">{{ m.kind === 'ATUALIZACAO' ? 'Saldo atual (o que o app do banco mostra)' : 'Valor' }}</label>
              <span class="money"><input id="ma" name="ma" type="number" step="0.01" min="0" [(ngModel)]="m.amount" required></span>
            </div>
          }
          @if (m.kind !== 'ATUALIZACAO') {
            <div class="field">
              <label for="mw">{{ m.kind === 'APORTE' || m.kind === 'COMPRA' ? 'Sai da conta' : 'Entra na conta' }}</label>
              <select id="mw" name="mw" [(ngModel)]="m.walletId">
                <option value="">Não movimentar conta (só registrar)</option>
                @for (o of walletOptions(); track o.value) { <option [value]="o.value">{{ o.label }}</option> }
              </select>
            </div>
          }
          <div class="field">
            <label for="mn">Observações</label>
            <input id="mn" name="mn" [(ngModel)]="m.notes" maxlength="1000">
          </div>
          <div class="form-actions">
            @if (m.id) { <button class="btn danger" type="button" (click)="deleteMov(m)">Excluir</button> }
            <span class="spacer"></span>
            <button class="btn ghost" type="button" (click)="mov.set(null)">Cancelar</button>
            <button class="btn" type="submit" [disabled]="busy()">Salvar</button>
          </div>
        </form>
      </div>
    }
  `,
  styles: `
    .alloc { margin-bottom: 16px; }
    .split { display: flex; gap: 16px; flex-wrap: wrap; color: var(--muted); margin-bottom: 12px; font-size: 13px; }
    .split strong { color: var(--text); font-variant-numeric: tabular-nums; }
    .bars { list-style: none; margin: 0; padding: 0; display: grid; gap: 10px; }
    .bar-label { display: flex; justify-content: space-between; gap: 8px; font-size: 13px; margin-bottom: 4px; }
    .bar-label .val { color: var(--text); font-weight: 600; font-variant-numeric: tabular-nums; }
    .track { height: 8px; background: var(--surface-2); border-radius: 4px; overflow: hidden; }
    .fill { height: 100%; background: var(--brand); border-radius: 4px; min-width: 2px; }
    tr.clickable { cursor: pointer; }
    tr.clickable:hover td { background: var(--surface-2); }
    tr.dim td:not(.actions) { opacity: .6; }
    td .tag { margin-left: 4px; }
    .small { font-size: 12px; }
    .meta, .mob { display: none; font-size: 12px; margin-top: 2px; }
    .detail-head { display: flex; justify-content: space-between; gap: 12px; align-items: flex-start; margin-bottom: 12px; }
    .detail-head h2 { margin-bottom: 2px; }
    .kpis.compact { grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); }
    .kpis.compact .value { font-size: 18px; }
    label.check { display: flex; gap: 8px; align-items: center; font-weight: 500; margin-bottom: 12px; }
    @media (max-width: 700px) {
      th.wide, td.wide { display: none; }
      .meta, .mob { display: block; }
    }
  `,
})
export class InvestmentsComponent implements OnInit {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  readonly types = INVESTMENT_TYPES;
  readonly typeLabel = INVESTMENT_TYPE_LABEL;
  readonly indexerLabel = INDEXER_LABEL;
  readonly movLabel = MOVEMENT_LABEL;
  readonly indexers: Indexer[] = ['CDI', 'SELIC', 'PRE', 'IPCA', 'POUPANCA', 'MANUAL'];
  readonly today = isoDate();

  data = signal<Portfolio | null>(null);
  accounts = signal<BankAccount[]>([]);
  detail = signal<InvestmentDetail | null>(null);
  form = signal<InvForm | null>(null);
  mov = signal<MovForm | null>(null);
  movInv = signal<Investment | null>(null);
  error = signal('');
  formError = signal('');
  detailError = signal('');
  busy = signal(false);

  activeAccounts = computed(() => this.accounts().filter(a => !a.archived));
  walletOptions = computed<Option[]>(() => this.activeAccounts().flatMap(a => a.wallets.filter(w => !w.archived)
    .map(w => ({ value: w.id, label: a.wallets.length > 1 ? `${a.name} · ${w.name}` : a.name }))));

  ngOnInit(): void {
    this.api.accounts().subscribe({ next: a => this.accounts.set(a), error: e => this.error.set(errorMessage(e)) });
    this.load();
    const id = this.route.snapshot.queryParamMap.get('id');
    if (id) this.openId(id);
  }

  private load(): void {
    this.api.portfolio().subscribe({
      next: p => { this.data.set(p); this.error.set(''); },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  // --- apresentação
  how(i: Investment): string {
    if (i.byQuantity) {
      return i.quantity ? `${this.qty(i.quantity)} × ${this.price(i.lastPrice)}` : 'sem posição';
    }
    switch (i.indexer) {
      case 'CDI': case 'SELIC': return `${this.num(i.rate)}% do ${i.indexer === 'CDI' ? 'CDI' : 'Selic'}`;
      case 'PRE': return `Prefixado ${this.num(i.rate)}% a.a.`;
      case 'IPCA': return `IPCA + ${this.num(i.rate)}% a.a.`;
      case 'POUPANCA': return 'Poupança';
      default: return 'Valor informado manualmente';
    }
  }

  valueNote(i: Investment): string {
    if (i.byQuantity) return i.lastPriceDate ? `cotação de ${this.br(i.lastPriceDate)}` : 'sem cotação';
    if (i.ratesUntil) return `estimado até ${this.br(i.ratesUntil)}` + (i.lastUpdate ? ` (saldo informado em ${this.br(i.lastUpdate)})` : '');
    return i.lastUpdate ? `saldo informado em ${this.br(i.lastUpdate)}` : 'sem rendimento automático';
  }

  pct(part: number, total: number): string {
    return total > 0 ? pctFmt.format((part / total) * 100) + '%' : '—';
  }

  fmtPct(v: number): string {
    return (v > 0 ? '+' : '') + pctFmt.format(v) + '%';
  }

  share(value: number, p: Portfolio): number {
    const max = Math.max(0, ...p.byType.map(t => t.value));
    return max > 0 ? (value / max) * 100 : 0;
  }

  qty(v: number | null): string { return v == null ? '—' : qtyFmt.format(v); }
  price(v: number | null): string { return v == null ? '—' : (Math.abs(v) >= 1 ? priceFmt : smallPriceFmt).format(v); }
  private num(v: number | null): string { return v == null ? '' : new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 4 }).format(v); }
  private br(iso: string): string { const [y, m, d] = iso.split('-'); return `${d}/${m}/${y}`; }

  // --- detalhe
  open(i: Investment): void {
    this.openId(i.id);
  }

  private openId(id: string): void {
    this.detailError.set('');
    this.api.investment(id).subscribe({
      next: d => this.detail.set(d),
      error: e => this.error.set(errorMessage(e)),
    });
  }

  closeDetail(): void {
    this.detail.set(null);
    if (this.route.snapshot.queryParamMap.get('id')) this.router.navigate([], { queryParams: {} });
  }

  private refresh(d?: InvestmentDetail): void {
    this.load();
    // Depois de um movimento, mostra o investimento atualizado (confirmação do que foi gravado).
    if (d) this.detail.set(d);
    else if (this.detail()) this.openId(this.detail()!.investment.id);
  }

  // --- cadastro
  byQty(f: InvForm): boolean {
    return INVESTMENT_TYPES.find(t => t.key === f.type)?.byQuantity ?? false;
  }

  newInv(): void {
    this.formError.set('');
    this.form.set({ id: null, type: 'CAIXINHA', name: '', ticker: '', indexer: 'CDI', rate: 100, maturityDate: '',
      accountId: null, walletId: null, notes: '', archived: false, hasPosition: false, positionAmount: null,
      positionQuantity: null, positionPrice: null, positionDate: this.today });
  }

  editInv(i: Investment): void {
    this.formError.set('');
    this.form.set({ id: i.id, type: i.type, name: i.name, ticker: i.ticker ?? '', indexer: i.indexer ?? 'MANUAL',
      rate: i.rate, maturityDate: i.maturityDate ?? '', accountId: i.accountId, walletId: i.walletId,
      notes: i.notes ?? '', archived: i.archived, hasPosition: false, positionAmount: null, positionQuantity: null,
      positionPrice: null, positionDate: this.today });
  }

  setType(f: InvForm, t: InvestmentType): void {
    const def = INVESTMENT_TYPES.find(x => x.key === t)!;
    if (!f.id) {
      f.indexer = def.indexer;
      this.setIndexer(f, def.indexer);
    }
  }

  setIndexer(f: InvForm, x: Indexer): void {
    f.rate = x === 'CDI' || x === 'SELIC' ? 100 : x === 'PRE' || x === 'IPCA' ? null : null;
  }

  rateLabel(x: Indexer): string {
    return x === 'CDI' ? '% do CDI' : x === 'SELIC' ? '% da Selic' : x === 'PRE' ? 'Taxa (% ao ano)' : 'IPCA + (% ao ano)';
  }

  indexerHint(x: Indexer): string {
    switch (x) {
      case 'CDI': return 'Rende pelo CDI diário do Banco Central (ex.: caixinha 100%, CDB 110%).';
      case 'SELIC': return 'Rende pela Selic diária (ex.: Tesouro Selic = 100%).';
      case 'PRE': return 'Taxa fixa ao ano, por dia útil (252 no ano).';
      case 'IPCA': return 'IPCA do mês (último publicado, se o mês ainda não saiu) + a taxa ao ano.';
      case 'POUPANCA': return 'Regra da poupança: 0,5% ao mês com Selic acima de 8,5% a.a.; senão 70% da Selic (sem TR).';
      default: return 'Não rende sozinho: você informa o saldo quando quiser (movimento "Atualizar saldo").';
    }
  }

  namePlaceholder(f: InvForm): string {
    return this.byQty(f) ? 'Ex.: Petrobras, MXRF11, Bitcoin' : 'Ex.: Caixinha Nubank, CDB Inter 110%, Tesouro Selic 2029';
  }

  saveInv(f: InvForm): void {
    if (!f.name.trim()) return this.formError.set('Informe o nome.');
    const byQty = this.byQty(f);
    if (!byQty && (f.indexer === 'PRE' || f.indexer === 'IPCA' || f.indexer === 'CDI' || f.indexer === 'SELIC') && f.rate == null) {
      return this.formError.set('Informe a taxa.');
    }
    if (f.hasPosition && byQty && !(Number(f.positionQuantity) > 0 && Number(f.positionPrice) > 0)) {
      return this.formError.set('Informe a quantidade e o preço médio da posição atual.');
    }
    if (f.hasPosition && !byQty && !(Number(f.positionAmount) > 0)) return this.formError.set('Informe o saldo atual.');
    const req: InvestmentRequest = {
      name: f.name.trim(), type: f.type, ticker: byQty ? (f.ticker.trim().toUpperCase() || null) : null,
      accountId: f.accountId, walletId: f.walletId, indexer: byQty ? null : f.indexer,
      rate: byQty || f.indexer === 'POUPANCA' || f.indexer === 'MANUAL' ? null : Number(f.rate),
      maturityDate: byQty ? null : (f.maturityDate || null), color: null, notes: f.notes || null, archived: f.archived,
    };
    this.busy.set(true);
    if (f.id) {
      this.api.updateInvestment(f.id, req).subscribe({
        next: () => { this.busy.set(false); this.form.set(null); this.refresh(); },
        error: e => { this.busy.set(false); this.formError.set(errorMessage(e)); },
      });
      return;
    }
    this.api.createInvestment(req).subscribe({
      next: inv => {
        if (!f.hasPosition) { this.busy.set(false); this.form.set(null); this.load(); return; }
        const pos: MovementRequest = byQty
          ? { kind: 'COMPRA', date: f.positionDate, amount: null, quantity: Number(f.positionQuantity),
              unitPrice: Number(f.positionPrice), fees: 0, walletId: null, notes: 'Posição inicial', allowDuplicate: true }
          : { kind: 'APORTE', date: f.positionDate, amount: Number(f.positionAmount), quantity: null, unitPrice: null,
              fees: null, walletId: null, notes: 'Saldo inicial', allowDuplicate: true };
        this.api.addMovement(inv.id, pos).subscribe({
          next: () => { this.busy.set(false); this.form.set(null); this.load(); },
          error: e => { this.busy.set(false); this.form.set(null); this.load(); this.error.set('Investimento criado, mas a posição inicial falhou: ' + errorMessage(e)); },
        });
      },
      error: e => { this.busy.set(false); this.formError.set(errorMessage(e)); },
    });
  }

  deleteInv(f: InvForm): void {
    if (!f.id || !confirm(`Excluir "${f.name}"? Só é possível sem movimentos (senão, arquive).`)) return;
    this.api.deleteInvestment(f.id).subscribe({
      next: () => { this.form.set(null); this.detail.set(null); this.load(); },
      error: e => this.formError.set(errorMessage(e)),
    });
  }

  // --- movimentos
  movKinds(): MovementKind[] {
    return this.movInv()?.byQuantity ? ['COMPRA', 'VENDA', 'PROVENTO', 'ATUALIZACAO'] : ['APORTE', 'RESGATE', 'PROVENTO', 'ATUALIZACAO'];
  }

  movButton(k: MovementKind): string {
    if (k === 'ATUALIZACAO') return this.movInv()?.byQuantity ? 'Atualizar cotação' : 'Atualizar saldo';
    return MOVEMENT_LABEL[k];
  }

  movHint(k: MovementKind): string {
    switch (k) {
      case 'APORTE': return 'Dinheiro que sai da conta para o investimento (não conta como despesa).';
      case 'RESGATE': return 'Dinheiro que volta do investimento para a conta (não conta como receita).';
      case 'COMPRA': return 'Compra de cotas/ações: sai da conta o total (quantidade × preço + taxas).';
      case 'VENDA': return 'Venda: volta para a conta quantidade × preço − taxas; o lucro é calculado pelo preço médio.';
      case 'PROVENTO': return 'Dividendos, JCP, rendimentos de FII ou juros: entram na conta como receita.';
      default: return this.movInv()?.byQuantity ? 'Cotação de hoje: o valor atual passa a usar este preço.'
        : 'Saldo que o app do banco mostra: o valor passa a ser este e continua rendendo a partir dele.';
    }
  }

  newMov(i: Investment): void {
    this.formError.set('');
    this.movInv.set(i);
    this.mov.set({ id: null, kind: i.byQuantity ? 'COMPRA' : 'APORTE', date: this.today, amount: null, quantity: null,
      unitPrice: null, fees: null, walletId: i.walletId ?? '', notes: '', duplicate: '' });
  }

  editMov(i: Investment, m: Movement): void {
    this.formError.set('');
    this.movInv.set(i);
    this.mov.set({ id: m.id, kind: m.kind, date: m.date, amount: m.kind === 'COMPRA' || m.kind === 'VENDA' ? null : m.amount,
      quantity: m.quantity, unitPrice: m.unitPrice, fees: m.fees || null, walletId: m.walletId ?? '',
      notes: m.notes ?? '', duplicate: '' });
  }

  setMovKind(m: MovForm, k: MovementKind): void {
    m.kind = k;
    m.duplicate = '';
    if (k === 'ATUALIZACAO') m.walletId = '';
    else if (!m.walletId && !m.id) m.walletId = this.movInv()?.walletId ?? '';
  }

  tradeTotal(m: MovForm): number {
    const gross = (Number(m.quantity) || 0) * (Number(m.unitPrice) || 0);
    return m.kind === 'VENDA' ? gross - (Number(m.fees) || 0) : gross + (Number(m.fees) || 0);
  }

  saveMov(m: MovForm, allowDuplicate: boolean): void {
    const inv = this.movInv();
    if (!inv) return;
    const trade = m.kind === 'COMPRA' || m.kind === 'VENDA';
    const price = m.kind === 'ATUALIZACAO' && inv.byQuantity;
    if (trade && !(Number(m.quantity) > 0 && Number(m.unitPrice) > 0)) return this.formError.set('Informe a quantidade e o preço.');
    if (price && !(Number(m.unitPrice) > 0)) return this.formError.set('Informe a cotação.');
    if (!trade && !price && !(Number(m.amount) > 0 || (m.kind === 'ATUALIZACAO' && Number(m.amount) === 0 && m.amount !== null))) {
      return this.formError.set('Informe o valor.');
    }
    const req: MovementRequest = {
      kind: m.kind, date: m.date, amount: trade || price ? null : Number(m.amount),
      quantity: trade ? Number(m.quantity) : null, unitPrice: trade || price ? Number(m.unitPrice) : null,
      fees: trade ? Number(m.fees) || 0 : null, walletId: m.kind === 'ATUALIZACAO' ? null : (m.walletId || null),
      notes: m.notes || null, allowDuplicate,
    };
    this.busy.set(true);
    this.formError.set('');
    const call = m.id ? this.api.updateMovement(inv.id, m.id, req) : this.api.addMovement(inv.id, req);
    call.subscribe({
      next: d => { this.busy.set(false); this.mov.set(null); this.refresh(d); },
      error: e => {
        this.busy.set(false);
        if (e instanceof HttpErrorResponse && e.status === 409) m.duplicate = errorMessage(e);
        else this.formError.set(errorMessage(e));
        this.mov.set({ ...m });
      },
    });
  }

  deleteMov(m: MovForm): void {
    const inv = this.movInv();
    if (!inv || !m.id || !confirm('Excluir este movimento? O lançamento ligado na conta também é excluído.')) return;
    this.api.deleteMovement(inv.id, m.id).subscribe({
      next: d => { this.mov.set(null); this.refresh(d); },
      error: e => this.formError.set(errorMessage(e)),
    });
  }
}
