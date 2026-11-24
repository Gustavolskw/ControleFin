import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.token();
  // O token só é enviado para a própria API do site.
  const request = token && req.url.startsWith('/api/')
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;
  return next(request).pipe(
    catchError((err: HttpErrorResponse) => {
      // Sessão expirada ou invalidada (troca de senha, conta desativada): volta para o login.
      if (err.status === 401 && token && !req.url.startsWith('/api/auth/')) {
        auth.logout();
      }
      return throwError(() => err);
    }),
  );
};

/** Extrai a mensagem de erro amigável enviada pelo backend. */
export function errorMessage(err: unknown, fallback = 'Não foi possível concluir a operação.'): string {
  if (err instanceof HttpErrorResponse) {
    if (err.status === 0) return 'Servidor indisponível.';
    if (err.status === 429 && !err.error?.message) return 'Muitas tentativas. Aguarde um minuto e tente de novo.';
    return err.error?.message ?? fallback;
  }
  return fallback;
}
