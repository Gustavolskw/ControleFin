import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../core/api.service';
import { errorMessage } from '../core/auth.interceptor';
import { Category, CategoryKind } from '../core/models';

interface CatForm {
  id: string | null;
  name: string;
  kind: CategoryKind;
  parentId: string | null;
  archived: boolean;
}

/** Categorias de despesa e receita (um nível de subcategoria). */
@Component({
  selector: 'app-categories',
  imports: [FormsModule],
  template: `
    <div class="page-head">
      <h1>Categorias</h1>
      <label class="check"><input type="checkbox" [(ngModel)]="showArchived"> Mostrar arquivadas</label>
    </div>
    @if (error()) { <div class="error" role="alert">{{ error() }}</div> }

    <div class="grid">
      @for (kind of kinds; track kind) {
        <section class="card">
          <div class="head">
            <h2>{{ kind === 'DESPESA' ? 'Despesas' : 'Receitas' }}</h2>
            <button class="btn ghost small" type="button" (click)="newCat(kind, null)">+ Categoria</button>
          </div>
          <ul class="tree">
            @for (p of parents(kind); track p.id) {
              <li>
                <div class="item" [class.archived]="p.archived">
                  <strong>{{ p.name }}</strong>
                  @if (p.archived) { <span class="tag warn">arquivada</span> }
                  <span class="spacer"></span>
                  <button class="link" type="button" (click)="newCat(kind, p.id)">+ sub</button>
                  <button class="link" type="button" (click)="edit(p)">editar</button>
                </div>
                <ul>
                  @for (c of children(p.id); track c.id) {
                    <li class="item" [class.archived]="c.archived">
                      <span>{{ c.name }}</span>
                      @if (c.archived) { <span class="tag warn">arquivada</span> }
                      <span class="spacer"></span>
                      <button class="link" type="button" (click)="edit(c)">editar</button>
                    </li>
                  }
                </ul>
              </li>
            }
          </ul>
        </section>
      }
    </div>

    @if (form(); as f) {
      <div class="backdrop" (click)="form.set(null)">
        <form class="card modal" (click)="$event.stopPropagation()" (ngSubmit)="save(f)">
          <h2>{{ f.id ? 'Editar categoria' : 'Nova categoria' }} de {{ f.kind === 'DESPESA' ? 'despesa' : 'receita' }}</h2>
          @if (formError()) { <div class="error">{{ formError() }}</div> }
          <div class="field">
            <label for="cn">Nome</label>
            <input id="cn" name="cn" [(ngModel)]="f.name" maxlength="60" required>
          </div>
          <div class="field">
            <label for="cp">Dentro de</label>
            <select id="cp" name="cp" [(ngModel)]="f.parentId">
              <option [ngValue]="null">— (categoria principal)</option>
              @for (p of parents(f.kind); track p.id) {
                @if (p.id !== f.id) { <option [value]="p.id">{{ p.name }}</option> }
              }
            </select>
          </div>
          @if (f.id) {
            <label class="check"><input type="checkbox" name="ca" [(ngModel)]="f.archived"> Arquivada (não aparece para novos lançamentos)</label>
          }
          <div class="form-actions">
            @if (f.id) { <button class="btn danger" type="button" (click)="remove(f)">Excluir</button> }
            <span class="spacer"></span>
            <button class="btn ghost" type="button" (click)="form.set(null)">Cancelar</button>
            <button class="btn" type="submit" [disabled]="busy()">Salvar</button>
          </div>
        </form>
      </div>
    }
  `,
  styles: `
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; align-items: start; }
    .head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
    .head h2 { margin: 0; }
    .tree, .tree ul { list-style: none; margin: 0; padding: 0; }
    .tree ul { padding-left: 18px; }
    .item { display: flex; align-items: center; gap: 8px; padding: 5px 0; border-bottom: 1px solid var(--border); }
    .item.archived { opacity: .6; }
    .spacer { flex: 1; }
    .link { background: none; border: 0; color: var(--accent); cursor: pointer; font: inherit; font-size: 12px; padding: 2px 4px; }
    @media (max-width: 800px) { .grid { grid-template-columns: 1fr; } }
  `,
})
export class CategoriesComponent implements OnInit {
  private api = inject(ApiService);

  readonly kinds: CategoryKind[] = ['DESPESA', 'RECEITA'];
  categories = signal<Category[]>([]);
  showArchived = false;
  error = signal('');
  formError = signal('');
  busy = signal(false);
  form = signal<CatForm | null>(null);

  ngOnInit(): void {
    this.load();
  }

  private load(): void {
    this.api.categories().subscribe({ next: c => this.categories.set(c), error: e => this.error.set(errorMessage(e)) });
  }

  parents(kind: CategoryKind): Category[] {
    return this.categories().filter(c => c.kind === kind && !c.parentId && (this.showArchived || !c.archived));
  }

  children(parentId: string): Category[] {
    return this.categories().filter(c => c.parentId === parentId && (this.showArchived || !c.archived));
  }

  newCat(kind: CategoryKind, parentId: string | null): void {
    this.formError.set('');
    this.form.set({ id: null, name: '', kind, parentId, archived: false });
  }

  edit(c: Category): void {
    this.formError.set('');
    this.form.set({ id: c.id, name: c.name, kind: c.kind, parentId: c.parentId, archived: c.archived });
  }

  save(f: CatForm): void {
    if (!f.name.trim()) return this.formError.set('Informe o nome.');
    this.busy.set(true);
    const req = { name: f.name, kind: f.kind, parentId: f.parentId, color: null, archived: f.archived };
    const call = f.id ? this.api.updateCategory(f.id, req) : this.api.createCategory(req);
    call.subscribe({
      next: () => { this.busy.set(false); this.form.set(null); this.load(); },
      error: e => { this.busy.set(false); this.formError.set(errorMessage(e)); },
    });
  }

  remove(f: CatForm): void {
    if (!f.id || !confirm(`Excluir a categoria ${f.name}? Se estiver em uso, arquive em vez de excluir.`)) return;
    this.api.deleteCategory(f.id).subscribe({
      next: () => { this.form.set(null); this.load(); },
      error: e => this.formError.set(errorMessage(e)),
    });
  }
}
