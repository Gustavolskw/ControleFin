/** Cliente da API externa do ControleFin (única coisa com que o MCP conversa). */
export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

export const TOKEN_FORMAT = /^cf_[A-Za-z0-9_-]{43}$/;

export class ExternalApi {
  constructor(private readonly baseUrl: string) {}

  async request<T = unknown>(pat: string, method: string, path: string, body?: unknown,
                             query?: Record<string, unknown>): Promise<T> {
    const url = new URL(this.baseUrl + path);
    for (const [k, v] of Object.entries(query ?? {})) {
      if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v));
    }
    let res: Response;
    try {
      res = await fetch(url, {
        method,
        headers: {
          Authorization: `Bearer ${pat}`,
          Accept: 'application/json',
          ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        },
        body: body !== undefined ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(90_000),
      });
    } catch {
      throw new ApiError(502, 'ControleFin indisponível no momento. Tente de novo em instantes.');
    }
    const text = await res.text();
    let data: unknown = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      data = null;
    }
    if (!res.ok) {
      const msg = (data as { message?: string } | null)?.message;
      if (res.status === 401) {
        throw new ApiError(401, 'O token do ControleFin foi revogado ou venceu. Crie outro em Meu perfil → Acesso do '
          + 'Claude e reconecte o conector.');
      }
      if (res.status === 403) {
        throw new ApiError(403, 'Este token é só de consulta. Para lançar, crie um token "Ler e lançar" e reconecte.');
      }
      throw new ApiError(res.status, msg ?? `Erro ${res.status} no ControleFin.`);
    }
    return data as T;
  }

  /** Confere se o token pessoal é válido (usado na tela de autorização e no refresh). */
  async check(pat: string): Promise<'ok' | 'invalid' | 'unavailable'> {
    if (!TOKEN_FORMAT.test(pat)) return 'invalid';
    try {
      await this.request(pat, 'GET', '/imports');
      return 'ok';
    } catch (e) {
      return e instanceof ApiError && e.status === 401 ? 'invalid' : 'unavailable';
    }
  }
}
