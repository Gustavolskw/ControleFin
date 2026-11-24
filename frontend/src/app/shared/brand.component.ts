import { Component, input } from '@angular/core';

/** Marca ControleFin: ícone de barras crescentes + nome. */
@Component({
  selector: 'app-brand',
  template: `
    <span class="brand" [class.large]="large()">
      <svg viewBox="0 0 32 32" aria-hidden="true">
        <rect width="32" height="32" rx="8" class="bg"/>
        <rect x="7" y="17" width="4" height="8" rx="1.5" class="bar"/>
        <rect x="14" y="12" width="4" height="13" rx="1.5" class="bar"/>
        <rect x="21" y="7" width="4" height="18" rx="1.5" class="bar hi"/>
      </svg>
      <span class="name">Controle<strong>Fin</strong></span>
    </span>
  `,
  styles: `
    .brand { display: inline-flex; align-items: center; gap: 8px; color: var(--text); }
    svg { width: 26px; height: 26px; flex: none; }
    .bg { fill: var(--brand); }
    .bar { fill: #E9F6F2; }
    .bar.hi { fill: var(--mint); }
    .name { font-size: 18px; font-weight: 500; letter-spacing: -.2px; }
    .name strong { color: var(--accent); font-weight: 750; }
    .large svg { width: 36px; height: 36px; }
    .large .name { font-size: 24px; }
  `,
})
export class BrandComponent {
  large = input(false);
}
