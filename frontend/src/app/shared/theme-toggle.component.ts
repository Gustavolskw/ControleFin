import { Component, inject } from '@angular/core';
import { ThemeService } from '../core/theme.service';

/** Alterna claro/escuro (sol/lua). */
@Component({
  selector: 'app-theme-toggle',
  template: `
    <button type="button" class="icon-btn toggle" (click)="theme.toggle()" [attr.aria-pressed]="theme.isDark()"
            [title]="theme.isDark() ? 'Modo claro' : 'Modo escuro'"
            [attr.aria-label]="theme.isDark() ? 'Ativar modo claro' : 'Ativar modo escuro'">
      @if (theme.isDark()) {
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>
      } @else {
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M21 12.8A9 9 0 1111.2 3a7 7 0 009.8 9.8z"/></svg>
      }
    </button>
  `,
  styles: `
    .toggle { display: inline-flex; align-items: center; justify-content: center; }
    svg { width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
  `,
})
export class ThemeToggleComponent {
  theme = inject(ThemeService);
}
