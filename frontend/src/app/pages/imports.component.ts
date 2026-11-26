import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../core/auth.interceptor';
import { BrlPipe, DateBrPipe, DateTimeBrPipe } from '../core/format';
import { Attachment, IMPORT_ITEM_LABEL, IMPORT_MODE_LABEL, ImportBatch, ImportItemStatus } from '../core/models';

/**
 * Importações enviadas pelo Claude: o que foi lançado, o que ficou pendente (modo Analisar), duplicados ignorados,
 * erros e os arquivos enviados. Daqui dá para confirmar os pendentes de uma vez ou desfazer a importação.
 */
@Component({
  selector: 'app-imports',
  imports: [RouterLink, BrlPipe, DateBrPipe, DateTimeBrPipe],
  template: `
    <div class="page-head">
      <h1>Importações</h1>
      <p class="sub">Lotes enviados pelo Claude. <strong>Analisar</strong> entra como pendente para você revisar aqui;
        <strong>Confirmado</strong> já conta no saldo; <strong>Remover</strong> cancela ou estorna compras (nunca apaga).</p>
    </div>
    @if (error()) { <div class="error" role="alert">{{ error() }}</div> }

    <section class="card">
      <div class="table-wrap">
        <table class="data">
          <thead><tr><th>Quando</th><th>Modo</th><th class="wide">Token</th><th>Resultado</th><th class="wide">Arquivos</th></tr></thead>
          <tbody>
            @for (b of batches(); track b.id) {
              <tr class="clickable" [class.selected]="b.id === selected()?.id" (click)="open(b.id)">
                <td>{{ b.createdAt | dateTimeBr }}
                  @if (b.note) { <div class="muted small">{{ b.note }}</div> }</td>
                <td><span class="tag" [class.accent]="b.mode === 'CONFIRMADO'" [class.warn]="b.mode === 'ANALISAR'">{{ modeLabel[b.mode] }}</span>
                  @if (b.status === 'DESFEITO') { <span class="tag">Desfeita</span> }</td>
                <td class="wide muted">{{ b.tokenName ?? '—' }}</td>
                <td><div class="summary">
                  @if (b.created) { <span>{{ b.created }} lançado(s)</span> }
                  @if (undone(b)) { <span class="muted">{{ undone(b) }} desfeito(s)</span> }
                  @if (b.pending) { <span class="warn-text">{{ b.pending }} pendente(s)</span> }
                  @if (b.removed) { <span>{{ b.removed }} removido(s)</span> }
                  @if (b.duplicates) { <span class="muted">{{ b.duplicates }} duplicado(s)</span> }
                  @if (b.errors) { <span class="neg">{{ b.errors }} erro(s)</span> }
                </div></td>
                <td class="wide">{{ b.attachments.length || '—' }}</td>
              </tr>
            } @empty {
              <tr><td colspan="5" class="empty">{{ loaded() ? 'Nenhuma importação ainda. Crie um token em Meu perfil para o Claude enviar lançamentos.' : 'Carregando…' }}</td></tr>
            }
          </tbody>
        </table>
      </div>
    </section>

    @if (selected(); as b) {
      <section class="card detail">
        <div class="detail-head">
          <h2>Importação de {{ b.createdAt | dateTimeBr }}</h2>
          <span class="tag">{{ modeLabel[b.mode] }}</span>
          @if (b.status === 'DESFEITO') { <span class="tag">Desfeita em {{ b.undoneAt | dateTimeBr }}</span> }
          <span class="spacer"></span>
          @if (b.status !== 'DESFEITO' && b.pending > 0) {
            <button class="btn" type="button" (click)="confirmAll(b)" [disabled]="busy()">Confirmar {{ b.pending }} pendente(s)</button>
          }
          @if (b.status !== 'DESFEITO' && b.mode !== 'REMOVER' && (b.created + b.pending) > 0) {
            <button class="btn ghost danger-text" type="button" (click)="undo(b)" [disabled]="busy()">Desfazer importação</button>
          }
        </div>
        @if (b.note) { <p class="muted">{{ b.note }}</p> }

        @if (b.attachments.length) {
          <div class="files">
            <span class="muted small">Arquivos enviados:</span>
            @for (a of b.attachments; track a.id) {
              <button class="btn ghost small" type="button" (click)="download(a)" [title]="a.mimeType">⭳ {{ a.name }} <span class="muted">({{ size(a.size) }})</span></button>
            }
          </div>
        }

        <div class="table-wrap">
          <table class="data">
            <thead><tr><th class="wide">#</th><th class="wide">Data</th><th>Descrição</th><th class="r">Valor</th><th>Situação</th></tr></thead>
            <tbody>
              @for (i of b.items; track i.position) {
                <tr>
                  <td class="muted wide">{{ i.position + 1 }}</td>
                  <td class="wide">{{ i.date | dateBr }}</td>
                  <td>{{ i.description ?? '—' }}
                    <div class="meta muted small">{{ i.date | dateBr }}</div>
                    @if (i.message) { <div class="muted small">{{ i.message }}</div> }</td>
                  <td class="money-cell">{{ i.amount != null ? (i.amount | brl) : '—' }}</td>
                  <td>
                    <span class="tag" [class.ok]="ok(i.status)" [class.warn]="i.status === 'PENDENTE'" [class.danger]="i.status === 'ERRO'">{{ itemLabel[i.status] }}</span>
                    @if (i.transactionId && i.description) {
                      <a class="small" routerLink="/lancamentos" [queryParams]="{ q: i.description, mes: i.date?.slice(0, 7) }">ver</a>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    }
  `,
  styles: `
    section.card { margin-bottom: 16px; }
    tr.clickable { cursor: pointer; }
    tr.clickable:hover td, tr.selected td { background: var(--surface-2); }
    .summary { display: flex; flex-wrap: wrap; gap: 4px 10px; font-size: 13px; }
    .warn-text { color: var(--warn); font-weight: 600; }
    .small { font-size: 12px; }
    .detail-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
    .detail-head h2 { margin: 0; }
    .spacer { flex: 1; }
    .files { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-bottom: 12px; }
    .danger-text { color: var(--danger); }
    td .tag { margin-right: 4px; }
    .meta { display: none; }
    @media (max-width: 700px) { th.wide, td.wide { display: none; } .meta { display: block; } }
  `,
})
export class ImportsComponent implements OnInit {
  private api = inject(ApiService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  readonly modeLabel = IMPORT_MODE_LABEL;
  readonly itemLabel = IMPORT_ITEM_LABEL;
  batches = signal<ImportBatch[]>([]);
  selected = signal<ImportBatch | null>(null);
  loaded = signal(false);
  error = signal('');
  busy = signal(false);

  ngOnInit(): void {
    this.load();
    const id = this.route.snapshot.queryParamMap.get('lote');
    if (id) this.open(id);
  }

  private load(): void {
    this.api.imports().subscribe({
      next: list => {
        this.batches.set(list);
        this.loaded.set(true);
        if (!this.selected() && list.length) this.open(list[0].id);
      },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  open(id: string): void {
    this.router.navigate([], { queryParams: { lote: id }, replaceUrl: true });
    this.api.importBatch(id).subscribe({
      next: b => { this.selected.set(b); this.error.set(''); },
      error: e => this.error.set(errorMessage(e)),
    });
  }

  undone(b: ImportBatch): number {
    return b.items.filter(i => i.status === 'DESFEITO').length;
  }

  ok(s: ImportItemStatus): boolean {
    return s === 'CRIADO' || s === 'REMOVIDO' || s === 'ESTORNADO';
  }

  size(bytes: number): string {
    return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1).replace('.', ',')} MB`;
  }

  download(a: Attachment): void {
    this.api.downloadAttachment(a);
  }

  confirmAll(b: ImportBatch): void {
    if (!confirm(`Confirmar os ${b.pending} lançamento(s) pendente(s) desta importação? Eles passam a contar no saldo e nas faturas.`)) return;
    this.busy.set(true);
    this.api.confirmImport(b.id).subscribe({
      next: r => { this.busy.set(false); this.selected.set(r); this.load(); },
      error: e => { this.busy.set(false); this.error.set(errorMessage(e)); },
    });
  }

  undo(b: ImportBatch): void {
    if (!confirm('Desfazer esta importação? Tudo o que ela lançou fica cancelado (o histórico é mantido).')) return;
    this.busy.set(true);
    this.api.undoImport(b.id).subscribe({
      next: r => { this.busy.set(false); this.selected.set(r); this.load(); },
      error: e => { this.busy.set(false); this.error.set(errorMessage(e)); },
    });
  }
}
