import { randomUUID } from 'node:crypto';
import express, { type Request, type Response, Router } from 'express';
import { ExternalApi, TOKEN_FORMAT } from './api.js';
import type { Config } from './config.js';
import { esc, page, STYLE } from './pages.js';
import { now, safeEqual, Sealer, sha256b64url, UsedIds } from './seal.js';

/**
 * Servidor de autorização OAuth 2.1 do conector do Claude, sem login nem cadastro próprios:
 * a "credencial" que o usuário apresenta é o token pessoal (cf_...) criado no site, em Meu perfil.
 *
 * - Descoberta: RFC 9728 (recurso protegido) + RFC 8414 (servidor de autorização).
 * - Registro dinâmico (RFC 7591) sem estado: o client_id é um envelope cifrado com as redirect_uris permitidas.
 *   Só são aceitos os retornos do Claude (claude.ai / claude.com) e loopback (Claude Code).
 * - Código de autorização com PKCE S256 obrigatório, uso único, 5 minutos.
 * - Access token de 1 h e refresh token de 30 dias, com rotação (o usado deixa de valer). Ambos levam o token
 *   pessoal cifrado: o backend continua sendo a autoridade (revogou no site, parou de funcionar).
 */
export const ACCESS_TTL = 3600;
export const REFRESH_TTL = 30 * 24 * 3600;
const CODE_TTL = 300;
export const SCOPE = 'controlefin';

const CLAUDE_CALLBACKS = new Set(['https://claude.ai/api/mcp/auth_callback', 'https://claude.com/api/mcp/auth_callback']);

/** Retornos aceitos: os do Claude (exatos) ou loopback http em qualquer porta (Claude Code). */
export function redirectAllowed(uri: string): boolean {
  if (CLAUDE_CALLBACKS.has(uri)) return true;
  try {
    const u = new URL(uri);
    return u.protocol === 'http:' && (u.hostname === 'localhost' || u.hostname === '127.0.0.1')
      && !u.username && !u.password && !u.hash;
  } catch {
    return false;
  }
}

/** A redirect_uri pedida bate com uma registrada (loopback compara sem a porta, RFC 8252 §7.3). */
export function redirectMatches(registered: string[], uri: string): boolean {
  if (registered.includes(uri)) return true;
  let u: URL;
  try {
    u = new URL(uri);
  } catch {
    return false;
  }
  if (u.protocol !== 'http:' || (u.hostname !== 'localhost' && u.hostname !== '127.0.0.1')) return false;
  return registered.some(r => {
    try {
      const x = new URL(r);
      return x.protocol === 'http:' && x.hostname === u.hostname && x.pathname === u.pathname && x.search === u.search;
    } catch {
      return false;
    }
  });
}

interface ClientInfo { r: string[]; n: string }
interface CodeInfo { pat: string; c: string; ru: string; cc: string; jti: string; exp: number }
export interface AccessInfo { pat: string; c: string; exp: number }
interface RefreshInfo { pat: string; c: string; jti: string; exp: number }

export function oauthRouter(cfg: Config, sealer: Sealer, api: ExternalApi): Router {
  const router = Router();
  const used = new UsedIds();
  const issuer = cfg.publicUrl;

  const resourceMetadata = {
    resource: `${issuer}/mcp`,
    authorization_servers: [issuer],
    scopes_supported: [SCOPE],
    bearer_methods_supported: ['header'],
    resource_name: 'ControleFin',
  };
  router.get(['/.well-known/oauth-protected-resource', '/.well-known/oauth-protected-resource/mcp'],
    (_req, res) => { res.json(resourceMetadata); });

  router.get(['/.well-known/oauth-authorization-server', '/.well-known/openid-configuration'], (_req, res) => {
    res.json({
      issuer,
      authorization_endpoint: `${issuer}/oauth/authorize`,
      token_endpoint: `${issuer}/oauth/token`,
      registration_endpoint: `${issuer}/oauth/register`,
      response_types_supported: ['code'],
      grant_types_supported: ['authorization_code', 'refresh_token'],
      code_challenge_methods_supported: ['S256'],
      token_endpoint_auth_methods_supported: ['none'],
      scopes_supported: [SCOPE],
      service_documentation: `${issuer}/`,
    });
  });

  router.get('/oauth/style.css', (_req, res) => {
    res.type('text/css').set('Cache-Control', 'public, max-age=3600').send(STYLE);
  });

  // ------------------------------------------------------------------ registro dinâmico (RFC 7591)
  router.post('/oauth/register', express.json({ limit: '16kb' }), (req, res) => {
    const body = (req.body ?? {}) as { redirect_uris?: unknown; client_name?: unknown };
    const uris = Array.isArray(body.redirect_uris) ? body.redirect_uris.filter(u => typeof u === 'string') as string[] : [];
    if (uris.length === 0 || uris.length > 5 || !uris.every(redirectAllowed)) {
      res.status(400).json({ error: 'invalid_redirect_uri',
        error_description: 'Só são aceitos os retornos do Claude (claude.ai/claude.com) ou loopback.' });
      return;
    }
    const name = typeof body.client_name === 'string' ? body.client_name.slice(0, 80) : 'Claude';
    const clientId = sealer.seal('client', { r: uris, n: name } satisfies ClientInfo);
    res.status(201).json({
      client_id: clientId,
      client_id_issued_at: now(),
      client_name: name,
      redirect_uris: uris,
      token_endpoint_auth_method: 'none',
      grant_types: ['authorization_code', 'refresh_token'],
      response_types: ['code'],
      scope: SCOPE,
    });
  });

  // ------------------------------------------------------------------ autorização
  interface AuthParams {
    client_id: string; redirect_uri: string; state: string; code_challenge: string;
    code_challenge_method: string; response_type: string; scope: string; resource: string;
  }

  const readParams = (src: Record<string, unknown>): AuthParams => {
    const s = (k: string) => (typeof src[k] === 'string' ? (src[k] as string) : '');
    return {
      client_id: s('client_id'), redirect_uri: s('redirect_uri'), state: s('state'), code_challenge: s('code_challenge'),
      code_challenge_method: s('code_challenge_method'), response_type: s('response_type'), scope: s('scope'),
      resource: s('resource'),
    };
  };

  /** Valida cliente e retorno. Erros aqui NÃO redirecionam (não se confia na redirect_uri). */
  const validate = (p: AuthParams): { client: ClientInfo } | { error: string } => {
    const client = sealer.open<ClientInfo>('client', p.client_id);
    if (!client) return { error: 'Aplicativo desconhecido. Remova e adicione o conector de novo no Claude.' };
    if (!redirectMatches(client.r, p.redirect_uri)) return { error: 'Endereço de retorno não autorizado.' };
    return { client };
  };

  const redirectError = (res: Response, p: AuthParams, error: string, description: string) => {
    const u = new URL(p.redirect_uri);
    u.searchParams.set('error', error);
    u.searchParams.set('error_description', description);
    if (p.state) u.searchParams.set('state', p.state);
    u.searchParams.set('iss', issuer);
    res.redirect(302, u.toString());
  };

  const form = (res: Response, p: AuthParams, client: ClientInfo, error?: string) => {
    const host = new URL(p.redirect_uri).host;
    const hidden = (Object.keys(p) as (keyof AuthParams)[])
      .map(k => `<input type="hidden" name="${k}" value="${esc(p[k])}">`).join('');
    page(res, error ? 400 : 200, 'Conectar o Claude', `
<h1>Conectar ao ControleFin</h1>
<p><b>${esc(client.n)}</b> (retorno para <b>${esc(host)}</b>) quer acessar seus dados financeiros: consultar, lançar,
editar e estornar lançamentos.</p>
${error ? `<div class="error">${esc(error)}</div>` : ''}
<form method="post" action="/oauth/authorize" autocomplete="off">${hidden}
<label for="token">Seu token pessoal</label>
<input id="token" name="token" type="password" required placeholder="cf_..." spellcheck="false" autocapitalize="off">
<p class="muted">Crie o token no site do ControleFin em <b>Meu perfil → Acesso do Claude</b>. Use "Só consultar" se quiser que o
Claude apenas leia. Você pode revogar quando quiser.</p>
<div class="row"><button class="ghost" type="submit" name="action" value="deny">Cancelar</button>
<button type="submit" name="action" value="allow">Autorizar</button></div>
</form>`);
  };

  router.get('/oauth/authorize', (req, res) => {
    const p = readParams(req.query as Record<string, unknown>);
    const v = validate(p);
    if ('error' in v) return page(res, 400, 'Erro', `<h1>Não foi possível conectar</h1><div class="error">${esc(v.error)}</div>`);
    if (p.response_type !== 'code') return redirectError(res, p, 'unsupported_response_type', 'Use response_type=code.');
    if (!p.code_challenge || p.code_challenge_method !== 'S256') {
      return redirectError(res, p, 'invalid_request', 'PKCE S256 é obrigatório.');
    }
    form(res, p, v.client);
  });

  router.post('/oauth/authorize', express.urlencoded({ extended: false, limit: '16kb' }), async (req, res) => {
    const body = (req.body ?? {}) as Record<string, unknown>;
    const p = readParams(body);
    const v = validate(p);
    if ('error' in v) return page(res, 400, 'Erro', `<h1>Não foi possível conectar</h1><div class="error">${esc(v.error)}</div>`);
    if (p.response_type !== 'code' || !p.code_challenge || p.code_challenge_method !== 'S256') {
      return redirectError(res, p, 'invalid_request', 'Pedido de autorização inválido.');
    }
    if (body.action !== 'allow') return redirectError(res, p, 'access_denied', 'O usuário cancelou.');
    const pat = typeof body.token === 'string' ? body.token.trim() : '';
    if (!TOKEN_FORMAT.test(pat)) return form(res, p, v.client, 'O token tem o formato cf_ seguido de 43 caracteres.');
    const check = await api.check(pat);
    if (check === 'invalid') return form(res, p, v.client, 'Token inválido, revogado ou vencido.');
    if (check === 'unavailable') return form(res, p, v.client, 'ControleFin indisponível agora. Tente de novo.');

    const code = sealer.seal('code', {
      pat, c: sha256b64url(p.client_id), ru: p.redirect_uri, cc: p.code_challenge, jti: randomUUID(),
      exp: now() + CODE_TTL,
    } satisfies CodeInfo);
    const u = new URL(p.redirect_uri);
    u.searchParams.set('code', code);
    if (p.state) u.searchParams.set('state', p.state);
    u.searchParams.set('iss', issuer);
    res.redirect(302, u.toString());
  });

  // ------------------------------------------------------------------ token
  const tokenError = (res: Response, error: string, description: string, status = 400) => {
    res.status(status).set('Cache-Control', 'no-store').json({ error, error_description: description });
  };

  const issue = (res: Response, pat: string, c: string) => {
    res.set('Cache-Control', 'no-store').set('Pragma', 'no-cache').json({
      access_token: sealer.seal('access', { pat, c, exp: now() + ACCESS_TTL } satisfies AccessInfo),
      token_type: 'Bearer',
      expires_in: ACCESS_TTL,
      refresh_token: sealer.seal('refresh', { pat, c, jti: randomUUID(), exp: now() + REFRESH_TTL } satisfies RefreshInfo),
      scope: SCOPE,
    });
  };

  router.post('/oauth/token', express.urlencoded({ extended: false, limit: '16kb' }), async (req: Request, res) => {
    const b = (req.body ?? {}) as Record<string, string | undefined>;
    const clientId = b.client_id ?? '';
    if (!sealer.open<ClientInfo>('client', clientId)) return tokenError(res, 'invalid_client', 'Cliente desconhecido.', 401);
    const c = sha256b64url(clientId);

    if (b.grant_type === 'authorization_code') {
      const code = sealer.open<CodeInfo>('code', b.code);
      if (!code || !safeEqual(code.c, c)) return tokenError(res, 'invalid_grant', 'Código inválido ou vencido.');
      if (b.redirect_uri !== code.ru) return tokenError(res, 'invalid_grant', 'redirect_uri diferente da autorização.');
      if (!b.code_verifier || !/^[A-Za-z0-9._~-]{43,128}$/.test(b.code_verifier)
          || !safeEqual(sha256b64url(b.code_verifier), code.cc)) {
        return tokenError(res, 'invalid_grant', 'PKCE inválido.');
      }
      if (!used.use(code.jti, code.exp)) return tokenError(res, 'invalid_grant', 'Código já usado.');
      return issue(res, code.pat, c);
    }

    if (b.grant_type === 'refresh_token') {
      const rt = sealer.open<RefreshInfo>('refresh', b.refresh_token);
      if (!rt || !safeEqual(rt.c, c)) return tokenError(res, 'invalid_grant', 'Refresh token inválido ou vencido.');
      const check = await api.check(rt.pat);
      if (check === 'invalid') return tokenError(res, 'invalid_grant', 'O token pessoal foi revogado ou venceu.');
      if (check === 'unavailable') return tokenError(res, 'temporarily_unavailable', 'Tente de novo.', 503);
      // Rotação: cada refresh token vale uma vez (marcado só depois da checagem, para não queimar em falha temporária).
      if (!used.use(rt.jti, rt.exp)) return tokenError(res, 'invalid_grant', 'Refresh token já usado.');
      return issue(res, rt.pat, c);
    }

    tokenError(res, 'unsupported_grant_type', 'Use authorization_code ou refresh_token.');
  });

  return router;
}
