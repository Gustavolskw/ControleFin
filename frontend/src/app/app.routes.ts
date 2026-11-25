import { Routes } from '@angular/router';
import { adminGuard, authGuard, guestGuard } from './core/auth.guard';
import { AccountsComponent } from './pages/accounts.component';
import { AdminComponent } from './pages/admin.component';
import { CardsComponent } from './pages/cards.component';
import { CategoriesComponent } from './pages/categories.component';
import { DashboardComponent } from './pages/dashboard.component';
import { InvoicesComponent } from './pages/invoices.component';
import { LoginComponent } from './pages/login.component';
import { ProfileComponent } from './pages/profile.component';
import { RecoverComponent } from './pages/recover.component';
import { RecurrencesComponent } from './pages/recurrences.component';
import { RegisterComponent } from './pages/register.component';
import { TransactionsComponent } from './pages/transactions.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent, canActivate: [guestGuard] },
  { path: 'cadastro', component: RegisterComponent, canActivate: [guestGuard] },
  { path: 'recuperar-senha', component: RecoverComponent, canActivate: [guestGuard] },
  { path: 'painel', component: DashboardComponent, canActivate: [authGuard] },
  { path: 'lancamentos', component: TransactionsComponent, canActivate: [authGuard] },
  { path: 'contas', component: AccountsComponent, canActivate: [authGuard] },
  { path: 'cartoes', component: CardsComponent, canActivate: [authGuard] },
  { path: 'recorrencias', component: RecurrencesComponent, canActivate: [authGuard] },
  { path: 'cartoes/:id/faturas', component: InvoicesComponent, canActivate: [authGuard] },
  { path: 'categorias', component: CategoriesComponent, canActivate: [authGuard] },
  { path: 'perfil', component: ProfileComponent, canActivate: [authGuard] },
  { path: 'admin', component: AdminComponent, canActivate: [authGuard, adminGuard] },
  { path: '', pathMatch: 'full', redirectTo: 'painel' },
  { path: '**', redirectTo: 'painel' },
];
