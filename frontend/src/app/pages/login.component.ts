import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { errorMessage } from '../core/auth.interceptor';
import { AuthService } from '../core/auth.service';
import { BrandComponent } from '../shared/brand.component';
import { PasswordInputComponent } from '../shared/password-input.component';
import { ThemeToggleComponent } from '../shared/theme-toggle.component';

@Component({
  selector: 'app-login',
  imports: [FormsModule, RouterLink, BrandComponent, PasswordInputComponent, ThemeToggleComponent],
  template: `
    <div class="auth-wrap">
      <div class="auth-page card">
        <div class="top"><app-brand [large]="true" /><app-theme-toggle /></div>
        <h1>Entrar</h1>
        <p class="sub">Suas contas, cartões e gastos em um só lugar.</p>
        @if (error()) { <div class="error" role="alert">{{ error() }}</div> }
        <form (ngSubmit)="submit()">
          <div class="field">
            <label for="u">Usuário</label>
            <input id="u" name="u" [(ngModel)]="username" autocomplete="username" autocapitalize="off" required>
          </div>
          <div class="field">
            <label for="p">Senha</label>
            <app-password-input inputId="p" name="p" [(ngModel)]="password" autocomplete="current-password" />
          </div>
          <button class="btn" type="submit" [disabled]="loading() || !username || !password">Entrar</button>
        </form>
        <div class="links">
          <a routerLink="/cadastro">Tenho um convite</a>
          <a routerLink="/recuperar-senha">Esqueci minha senha</a>
        </div>
      </div>
    </div>
  `,
  styles: `.top { display: flex; align-items: center; justify-content: space-between; }`,
})
export class LoginComponent {
  private auth = inject(AuthService);
  private router = inject(Router);

  username = '';
  password = '';
  loading = signal(false);
  error = signal('');

  submit(): void {
    this.loading.set(true);
    this.error.set('');
    this.auth.login(this.username, this.password).subscribe({
      next: r => this.router.navigate([r.setupRequired ? '/perfil' : '/']),
      error: err => {
        this.error.set(errorMessage(err));
        this.loading.set(false);
      },
    });
  }
}
