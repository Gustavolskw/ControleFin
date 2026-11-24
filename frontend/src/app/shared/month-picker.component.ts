import { Component, model } from '@angular/core';
import { MonthBrPipe, addMonths, currentMonth } from '../core/format';

/** Navegação de mês: ‹ novembro de 2026 › (valor AAAA-MM, com two-way binding). */
@Component({
  selector: 'app-month-picker',
  imports: [MonthBrPipe],
  template: `
    <div class="mp">
      <button type="button" class="icon-btn" (click)="month.set(shift(-1))" aria-label="Mês anterior">‹</button>
      <span class="label" style="display: inline-block">{{ month() | monthBr }}</span>
      <button type="button" class="icon-btn" (click)="month.set(shift(1))" aria-label="Próximo mês">›</button>
      @if (month() !== today) {
        <button type="button" class="btn ghost small" (click)="month.set(today)">Hoje</button>
      }
    </div>
  `,
  styles: `
    .mp { display: inline-flex; align-items: center; gap: 8px; }
    .label { min-width: 150px; text-align: center; font-weight: 600; }
    .label::first-letter { text-transform: uppercase; }
  `,
})
export class MonthPickerComponent {
  month = model<string>(currentMonth());
  readonly today = currentMonth();

  shift(delta: number): string {
    return addMonths(this.month(), delta);
  }
}
