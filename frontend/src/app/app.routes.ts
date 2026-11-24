import { Routes } from '@angular/router';
import { adminGuard, authGuard, guestGuard } from './core/auth.guard';
import { AdminComponent } from './pages/admin.component';
import { DashboardComponent } from './pages/dashboard.component';
import { LoginComponent } from './pages/login.component';
import { ProfileComponent } from './pages/profile.component';
import { RecoverComponent } from './pages/recover.component';
import { RegisterComponent } from './pages/register.component';

export const routes: Routes = [
  { path: 'login', component: LoginComponent, canActivate: [guestGuard] },
  { path: 'cadastro', component: RegisterComponent, canActivate: [guestGuard] },
  { path: 'recuperar-senha', component: RecoverComponent, canActivate: [guestGuard] },
  { path: 'painel', component: DashboardComponent, canActivate: [authGuard] },
  { path: 'perfil', component: ProfileComponent, canActivate: [authGuard] },
  { path: 'admin', component: AdminComponent, canActivate: [authGuard, adminGuard] },
  { path: '', pathMatch: 'full', redirectTo: 'painel' },
  { path: '**', redirectTo: 'painel' },
];
