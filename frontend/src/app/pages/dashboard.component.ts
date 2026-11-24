import { Component, inject } from '@angular/core';
import { AuthService } from '../core/auth.service';

/** Painel inicial. Os cards de saldo (total, por conta e por carteira) chegam na fase 1. */
@Component({
  selector: 'app-dashboard',
  template: `
    <div class="page-head">
      <h1>Olá, {{ auth.username() }}</h1>
      <p class="sub">Painel financeiro</p>
    </div>
    <div class="card empty">
      <p><strong>Seu ambiente está pronto.</strong></p>
      <p>Na próxima etapa entram as contas, carteiras e o saldo total.</p>
    </div>
  `,
})
export class DashboardComponent {
  auth = inject(AuthService);
}
