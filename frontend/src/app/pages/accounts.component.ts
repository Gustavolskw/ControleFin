import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../core/auth.interceptor';
import { BrlPipe, isoDate } from '../core/format';
import { ACCOUNT_TYPES, AccountType, BankAccount, Holder, Wallet, WalletRequest, accountTypeLabel } from '../core/models';

interface AccountForm {
  id: string | null;
  name: string;
  type: AccountType;
  holder: Holder;
  color: string;
  archived: boolean;
  wallets: WalletRequest[];
}

interface WalletForm {
  id: string | null;
  accountId: string;
  name: string;
  initialBalance: number;
  archived: boolean;
}

interface AdjustForm {
  wallet: Wallet;
  balance: number;
  date: string;
  description: string;
}

/** Contas e carteiras (ex.: Wiipo com "Livre" e "VA/VR", cada uma com saldo próprio). */
@Component({
  selector: 'app-accounts',
  imports: [FormsModule, RouterLink, BrlPipe],
  template: `
    <div class="page-head">
      <h1>Contas</h1>
      <label class="check"><input type="checkbox" [(ngModel)]="showArchived"> Mostrar arquivadas</label>
      <button class="btn" type="button" (click)="newAccount()">Nova conta</button>
      <p class="sub">Cada conta tem uma ou mais carteiras com saldo próprio. O saldo total soma todas (PF, PJ e benefícios).</p>
    </div>
    @if (error()) { <div class="error" role="alert">{{ error() }}</div> }

    <div class="kpis">
      <div class="kpi main"><div class="label">Saldo total</div><div class="value">{{ total() | brl }}</div></div>
    </div>

    @if (loaded() && visible().length === 0) {
      <div class="card empty">Nenhuma conta ainda. Cadastre suas contas (Itaú, Nubank, Nubank PJ, Wiipo…).</div>
    }

    <div class="list">
      @for (a of visible(); track a.id) {
        <section class="card account" [class.archived]="a.archived" [style.border-left-color]="a.color || 'var(--brand)'">
          <div class="acc-head">
            <div class="title">
              <h2>{{ a.name }}</h2>
              <span class="tag">{{ typeLabel(a.type) }}</span>
              <span class="tag" [class.accent]="a.holder === 'PJ'">{{ a.holder }}</span>
              @if (a.archived) { <span class="tag warn">Arquivada</span> }
            </div>
            <div class="acc-total" [class.neg]="a.balance < 0">{{ a.balance | brl }}</div>
            <div class="head-actions">
              <a class="btn ghost small" routerLink="/lancamentos" [queryParams]="{ accountId: a.id }">Lançamentos</a>
              <button class="btn ghost small" type="button" (click)="editAccount(a)">Editar</button>
            </div>
          </div>
          <div class="table-wrap">
            <table class="data">
              <thead><tr><th>Carteira</th><th class="r">Saldo inicial</th><th class="r">Saldo atual</th><th></th></tr></thead>
              <tbody>
                @for (w of walletsOf(a); track w.id) {
                  <tr [class.muted]="w.archived">
                    <td>{{ w.name }} @if (w.archived) { <span class="tag warn">arquivada</span> }</td>
                    <td class="money-cell">{{ w.initialBalance | brl }}</td>
                    <td class="money-cell"><strong [class.neg]="w.balance < 0">{{ w.balance | brl }}</strong></td>
                    <td class="actions">
                      <button class="btn ghost small" type="button" (click)="openAdjust(w)" title="Informar o saldo real">Ajustar saldo</button>
                      <button class="btn ghost small" type="button" (click)="editWallet(w)">Editar</button>
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <button class="btn ghost small add" type="button" (click)="newWallet(a)">+ Carteira</button>
        </section>
      }
    </div>

    <!-- conta -->
    @if (accountForm(); as f) {
      <div class="backdrop" (click)="accountForm.set(null)">
        <form class="card modal" (click)="$event.stopPropagation()" (ngSubmit)="saveAccount(f)">
          <h2>{{ f.id ? 'Editar conta' : 'Nova conta' }}</h2>
          @if (formError()) { <div class="error">{{ formError() }}</div> }
          <div class="field">
            <label for="an">Nome</label>
            <input id="an" name="an" [(ngModel)]="f.name" maxlength="60" required placeholder="Ex.: Nubank, Itaú, Wiipo">
          </div>
          <div class="row">
            <div class="field">
              <label for="at">Tipo</label>
              <select id="at" name="at" [(ngModel)]="f.type">
                @for (t of types; track t.key) { <option [value]="t.key">{{ t.label }}</option> }
              </select>
            </div>
            <div class="field">
              <label for="ah">Titularidade</label>
              <select id="ah" name="ah" [(ngModel)]="f.holder">
                <option value="PF">Pessoa física (PF)</option>
                <option value="PJ">Pessoa jurídica (PJ)</option>
              </select>
            </div>
            <div class="field">
              <label for="ac">Cor</label>
              <input id="ac" name="ac" type="color" [(ngModel)]="f.color" style="height: 38px; padding: 2px">
            </div>
          </div>
          @if (!f.id) {
            <h3>Carteiras e saldo inicial</h3>
            <p class="hint" style="margin: -4px 0 8px">Uma conta comum tem só "Principal". Benefícios como a Wiipo podem ter
              várias (ex.: "Livre" e "VA/VR"), cada uma com seu saldo.</p>
            @for (w of f.wallets; track $index; let i = $index) {
              <div class="wallet-row">
                <input [name]="'wn' + i" [(ngModel)]="w.name" maxlength="40" required placeholder="Nome da carteira" aria-label="Nome da carteira">
                <span class="money"><input [name]="'wb' + i" type="number" step="0.01" [(ngModel)]="w.initialBalance" aria-label="Saldo inicial"></span>
                <button type="button" class="icon-btn" (click)="f.wallets.splice(i, 1)" [disabled]="f.wallets.length === 1" aria-label="Remover carteira">✕</button>
              </div>
            }
            <button type="button" class="btn ghost small" (click)="f.wallets.push({ name: '', initialBalance: 0 })">+ Carteira</button>
          } @else {
            <label class="check" style="margin-top: 4px"><input type="checkbox" name="aa" [(ngModel)]="f.archived"> Arquivada (some do painel, mantém o histórico)</label>
          }
          <div class="form-actions">
            @if (f.id) { <button class="btn danger" type="button" (click)="deleteAccount(f)">Excluir</button> }
            <span class="spacer"></span>
            <button class="btn ghost" type="button" (click)="accountForm.set(null)">Cancelar</button>
            <button class="btn" type="submit" [disabled]="busy()">Salvar</button>
          </div>
        </form>
      </div>
    }

    <!-- carteira -->
    @if (walletForm(); as f) {
      <div class="backdrop" (click)="walletForm.set(null)">
        <form class="card modal" (click)="$event.stopPropagation()" (ngSubmit)="saveWallet(f)">
          <h2>{{ f.id ? 'Editar carteira' : 'Nova carteira' }}</h2>
          @if (formError()) { <div class="error">{{ formError() }}</div> }
          <div class="field">
            <label for="wn">Nome</label>
            <input id="wn" name="wn" [(ngModel)]="f.name" maxlength="40" required placeholder="Ex.: VA/VR, Livre, Reserva">
          </div>
          <div class="field">
            <label for="wb">Saldo inicial</label>
            <span class="money"><input id="wb" name="wb" type="number" step="0.01" [(ngModel)]="f.initialBalance"></span>
            <p class="hint">Saldo no momento do cadastro. Para corrigir o saldo de hoje, use "Ajustar saldo".</p>
          </div>
          @if (f.id) {
            <label class="check"><input type="checkbox" name="wa" [(ngModel)]="f.archived"> Arquivada</label>
          }
          <div class="form-actions">
            @if (f.id) { <button class="btn danger" type="button" (click)="deleteWallet(f)">Excluir</button> }
            <span class="spacer"></span>
            <button class="btn ghost" type="button" (click)="walletForm.set(null)">Cancelar</button>
            <button class="btn" type="submit" [disabled]="busy()">Salvar</button>
          </div>
        </form>
      </div>
    }

    <!-- ajuste -->
    @if (adjustForm(); as f) {
      <div class="backdrop" (click)="adjustForm.set(null)">
        <form class="card modal" (click)="$event.stopPropagation()" (ngSubmit)="saveAdjust(f)">
          <h2>Ajustar saldo — {{ f.wallet.name }}</h2>
          <p class="hint" style="margin-top: -8px">Saldo no sistema: <strong>{{ f.wallet.balance | brl }}</strong>. Informe o saldo real
            (do app do banco); a diferença vira um lançamento de ajuste.</p>
          @if (formError()) { <div class="error">{{ formError() }}</div> }
          <div class="row">
            <div class="field">
              <label for="jb">Saldo real</label>
              <span class="money"><input id="jb" name="jb" type="number" step="0.01" [(ngModel)]="f.balance" required></span>
            </div>
            <div class="field">
              <label for="jd">Data</label>
              <input id="jd" name="jd" type="date" [(ngModel)]="f.date" required>
            </div>
          </div>
          <div class="field">
            <label for="jt">Descrição (opcional)</label>
            <input id="jt" name="jt" [(ngModel)]="f.description" maxlength="200" placeholder="Ajuste de saldo">
          </div>
          <div class="form-actions">
            <button class="btn ghost" type="button" (click)="adjustForm.set(null)">Cancelar</button>
            <button class="btn" type="submit" [disabled]="busy()">Ajustar</button>
          </div>
        </form>
      </div>
    }
  `,
  styles: `
    .list { display: grid; gap: 14px; }
    .account { border-left: 4px solid var(--brand); }
    .account.archived { opacity: .7; }
    .acc-head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 10px; }
    .title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; flex: 1; min-width: 200px; }
    .title h2 { margin: 0; }
    .acc-total { font-size: 18px; font-weight: 700; font-variant-numeric: tabular-nums; }
    .head-actions { display: flex; gap: 6px; }
    .add { margin-top: 10px; }
    .wallet-row { display: grid; grid-template-columns: 1fr 150px 34px; gap: 8px; margin-bottom: 8px; }
    tr.muted td { color: var(--muted); }
  `,
})
export class AccountsComponent implements OnInit {
  private api = inject(ApiService);

  readonly types = ACCOUNT_TYPES;
  readonly typeLabel = accountTypeLabel;
  accounts = signal<BankAccount[]>([]);
  loaded = signal(false);
  showArchived = false;
  error = signal('');
  formError = signal('');
  busy = signal(false);
  accountForm = signal<AccountForm | null>(null);
  walletForm = signal<WalletForm | null>(null);
  adjustForm = signal<AdjustForm | null>(null);

  total = computed(() => this.accounts().reduce((s, a) => s + a.balance, 0));

  ngOnInit(): void {
    this.load();
  }

  visible(): BankAccount[] {
    return this.accounts().filter(a => this.showArchived || !a.archived);
  }

  walletsOf(a: BankAccount): Wallet[] {
    return a.wallets.filter(w => this.showArchived || !w.archived);
  }

  private load(): void {
    this.api.accounts().subscribe({
      next: list => { this.accounts.set(list); this.loaded.set(true); },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  private done(): void {
    this.busy.set(false);
    this.accountForm.set(null);
    this.walletForm.set(null);
    this.adjustForm.set(null);
    this.load();
  }

  private fail(e: unknown): void {
    this.busy.set(false);
    this.formError.set(errorMessage(e));
  }

  // --- conta
  newAccount(): void {
    this.formError.set('');
    this.accountForm.set({ id: null, name: '', type: 'CORRENTE', holder: 'PF', color: '#0F5F56', archived: false,
      wallets: [{ name: 'Principal', initialBalance: 0 }] });
  }

  editAccount(a: BankAccount): void {
    this.formError.set('');
    this.accountForm.set({ id: a.id, name: a.name, type: a.type, holder: a.holder, color: a.color ?? '#0F5F56',
      archived: a.archived, wallets: [] });
  }

  saveAccount(f: AccountForm): void {
    if (!f.name.trim()) return this.formError.set('Informe o nome da conta.');
    if (!f.id && f.wallets.some(w => !w.name.trim())) return this.formError.set('Dê um nome a cada carteira.');
    this.busy.set(true);
    const req = { name: f.name, type: f.type, holder: f.holder, color: f.color, archived: f.archived,
      wallets: f.id ? undefined : f.wallets.map(w => ({ name: w.name, initialBalance: Number(w.initialBalance) || 0 })) };
    const call = f.id ? this.api.updateAccount(f.id, req) : this.api.createAccount(req);
    call.subscribe({ next: () => this.done(), error: e => this.fail(e) });
  }

  deleteAccount(f: AccountForm): void {
    if (!f.id || !confirm(`Excluir a conta ${f.name}? Só é possível se não houver lançamentos.`)) return;
    this.api.deleteAccount(f.id).subscribe({ next: () => this.done(), error: e => this.fail(e) });
  }

  // --- carteira
  newWallet(a: BankAccount): void {
    this.formError.set('');
    this.walletForm.set({ id: null, accountId: a.id, name: '', initialBalance: 0, archived: false });
  }

  editWallet(w: Wallet): void {
    this.formError.set('');
    this.walletForm.set({ id: w.id, accountId: w.accountId, name: w.name, initialBalance: w.initialBalance, archived: w.archived });
  }

  saveWallet(f: WalletForm): void {
    if (!f.name.trim()) return this.formError.set('Informe o nome da carteira.');
    this.busy.set(true);
    const body = { name: f.name, initialBalance: Number(f.initialBalance) || 0 };
    const call = f.id
      ? this.api.updateWallet(f.id, { ...body, archived: f.archived })
      : this.api.addWallet(f.accountId, body);
    call.subscribe({ next: () => this.done(), error: e => this.fail(e) });
  }

  deleteWallet(f: WalletForm): void {
    if (!f.id || !confirm(`Excluir a carteira ${f.name}? Só é possível se não houver lançamentos.`)) return;
    this.api.deleteWallet(f.id).subscribe({ next: () => this.done(), error: e => this.fail(e) });
  }

  // --- ajuste
  openAdjust(w: Wallet): void {
    this.formError.set('');
    this.adjustForm.set({ wallet: w, balance: w.balance, date: isoDate(), description: '' });
  }

  saveAdjust(f: AdjustForm): void {
    this.busy.set(true);
    this.api.adjustWallet(f.wallet.id, Number(f.balance), f.date, f.description || null)
      .subscribe({ next: () => this.done(), error: e => this.fail(e) });
  }
}
