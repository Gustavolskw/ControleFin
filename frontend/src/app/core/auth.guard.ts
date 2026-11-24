import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Exige login. Enquanto o primeiro acesso estiver pendente (senha inicial ou sem perguntas de segurança),
 * só a tela de perfil fica liberada (o backend aplica a mesma regra).
 */
export const authGuard: CanActivateFn = route => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (!auth.isLoggedIn()) return router.createUrlTree(['/login']);
  if (auth.setupRequired() && route.routeConfig?.path !== 'perfil') return router.createUrlTree(['/perfil']);
  return true;
};

/** Telas de administração (o backend também exige ROLE_ADMIN). */
export const adminGuard: CanActivateFn = () => {
  const auth = inject(AuthService);
  return auth.isAdmin() ? true : inject(Router).createUrlTree(['/']);
};

export const guestGuard: CanActivateFn = () =>
  inject(AuthService).isLoggedIn() ? inject(Router).createUrlTree(['/']) : true;
