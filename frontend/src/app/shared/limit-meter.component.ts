import { Component, computed, input } from '@angular/core';
import { BrlPipe } from '../core/format';

/** Barra de uso do limite: usado / limite, com o disponível em texto (a cor nunca é a única informação). */
@Component({
  selector: 'app-limit-meter',
  imports: [BrlPipe],
  template: `
    <div class="meter" role="meter" [attr.aria-valuenow]="used()" aria-valuemin="0" [attr.aria-valuemax]="limit()"
         [attr.aria-label]="'Limite usado: ' + pctText() + '%'">
      <div class="track"><div class="fill" [class.high]="pct() >= 90" [style.width.%]="pct()"></div></div>
      <div class="legend">
        <span>Usado <strong>{{ used() | brl }}</strong> ({{ pctText() }}%)</span>
        <span [class.neg]="available() < 0">Disponível <strong>{{ available() | brl }}</strong></span>
      </div>
    </div>
  `,
  styles: `
    .track { height: 8px; background: var(--surface-2); border-radius: 4px; overflow: hidden; }
    .fill { height: 100%; background: var(--brand); border-radius: 4px; min-width: 2px; }
    .fill.high { background: var(--warn); }
    .legend { display: flex; justify-content: space-between; gap: 8px; flex-wrap: wrap; font-size: 12px;
      color: var(--muted); margin-top: 6px; font-variant-numeric: tabular-nums; }
    .legend strong { color: var(--text); }
    .legend .neg strong { color: var(--danger); }
  `,
})
export class LimitMeterComponent {
  used = input.required<number>();
  limit = input.required<number>();
  available = input.required<number>();

  pct = computed(() => {
    const l = this.limit();
    return l > 0 ? Math.min(100, Math.max(0, (this.used() / l) * 100)) : (this.used() > 0 ? 100 : 0);
  });

  pctText = computed(() => {
    const l = this.limit();
    return l > 0 ? Math.round((this.used() / l) * 100).toString() : '—';
  });
}
