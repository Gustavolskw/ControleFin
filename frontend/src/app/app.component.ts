import { Component, computed, inject, signal } from '@angular/core';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { AuthService } from './core/auth.service';
import { ThemeService } from './core/theme.service';
import { BrandComponent } from './shared/brand.component';
import { ThemeToggleComponent } from './shared/theme-toggle.component';

interface NavLink { path: string; label: string; }

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, BrandComponent, ThemeToggleComponent],
  template: `
    @if (auth.isLoggedIn()) {
      <header class="topbar">
        <a class="home" routerLink="/" aria-label="ControleFin — início"><app-brand /></a>
        <button class="menu-toggle icon-btn" type="button" (click)="menuOpen.set(!menuOpen())"
                [attr.aria-expanded]="menuOpen()" aria-controls="main-nav" aria-label="Menu">☰</button>
        <nav id="main-nav" [class.open]="menuOpen()">
          @if (!auth.setupRequired()) {
            @for (l of links(); track l.path) {
              <a [routerLink]="l.path" routerLinkActive="active">{{ l.label }}</a>
            }
          }
        </nav>
        <span class="user">
          <app-theme-toggle />
          <a routerLink="/perfil" routerLinkActive="active" title="Meu perfil">{{ auth.username() }}</a>
          <button class="btn ghost small" (click)="auth.logout()">Sair</button>
        </span>
      </header>
      <main><router-outlet /></main>
    } @else {
      <router-outlet />
    }
  `,
  styles: `
    .topbar {
      display: flex; align-items: center; gap: 16px; flex-wrap: wrap;
      padding: 10px 20px; position: sticky; top: 0; z-index: 5;
      background: color-mix(in srgb, var(--surface) 90%, transparent);
      backdrop-filter: blur(10px); border-bottom: 1px solid var(--border);
    }
    .home { text-decoration: none; display: flex; }
    nav { display: flex; gap: 2px; flex: 1; flex-wrap: wrap; }
    nav a { padding: 7px 12px; border-radius: 8px; color: var(--muted); text-decoration: none; white-space: nowrap; font-weight: 500; }
    nav a:hover { color: var(--text); background: var(--surface-2); }
    nav a.active { color: var(--accent); background: var(--accent-soft); font-weight: 600; }
    .user { display: flex; align-items: center; gap: 10px; color: var(--muted); }
    .user > a { color: var(--muted); text-decoration: none; padding: 6px 10px; border-radius: 8px; }
    .user > a:hover { color: var(--text); }
    .user > a.active { background: var(--accent-soft); color: var(--accent); font-weight: 600; }
    .menu-toggle { display: none; margin-left: auto; }
    main { max-width: 1240px; margin: 0 auto; padding: 22px 16px 48px; }
    @media (max-width: 900px) {
      .menu-toggle { display: inline-block; }
      nav { display: none; flex-basis: 100%; flex-direction: column; order: 3; }
      nav.open { display: flex; }
      .user { order: 2; }
    }
  `,
})
export class AppComponent {
  auth = inject(AuthService);
  /** Instancia o tema logo no início (aplica data-theme e acompanha o sistema). */
  theme = inject(ThemeService);
  menuOpen = signal(false);

  links = computed<NavLink[]>(() => {
    const base: NavLink[] = [
      { path: '/painel', label: 'Painel' },
      { path: '/lancamentos', label: 'Lançamentos' },
      { path: '/contas', label: 'Contas' },
      { path: '/cartoes', label: 'Cartões' },
      { path: '/categorias', label: 'Categorias' },
    ];
    return this.auth.isAdmin() ? [...base, { path: '/admin', label: 'Administração' }] : base;
  });

  constructor() {
    inject(Router).events.pipe(filter(e => e instanceof NavigationEnd)).subscribe(() => this.menuOpen.set(false));
  }
}
