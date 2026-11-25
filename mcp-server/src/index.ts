import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import express, { type NextFunction, type Request, type Response } from 'express';
import { pathToFileURL } from 'node:url';
import { ExternalApi } from './api.js';
import { type Config, loadConfig } from './config.js';
import { type AccessInfo, oauthRouter } from './oauth.js';
import { Sealer } from './seal.js';
import { buildServer } from './tools.js';
import { uploadRouter } from './uploads.js';

/**
 * Servidor MCP remoto do ControleFin (Streamable HTTP, sem estado). Rotas:
 *   /mcp                         MCP (exige access token OAuth)
 *   /.well-known/oauth-*         descoberta OAuth
 *   /oauth/*                     registro, autorização (com o token pessoal) e emissão de tokens
 *   /anexos/<link>               página para o usuário enviar o print/PDF de um lote
 * Só conversa com a API externa; não tem banco.
 */
export function createApp(cfg: Config): express.Express {
  const sealer = new Sealer(cfg.tokenKey);
  const api = new ExternalApi(cfg.externalApiUrl);
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', 'loopback, uniquelocal');

  app.get('/mcp-health', (_req, res) => { res.json({ status: 'ok' }); });
  app.use(oauthRouter(cfg, sealer, api));
  app.use(uploadRouter(sealer, api));

  const unauthorized = (res: Response, invalid: boolean) => {
    res.status(401)
      .set('WWW-Authenticate', `Bearer resource_metadata="${cfg.publicUrl}/.well-known/oauth-protected-resource"`
        + (invalid ? ', error="invalid_token", error_description="Token inválido ou vencido"' : ''))
      .json({ jsonrpc: '2.0', error: { code: -32001, message: 'Autenticação necessária.' }, id: null });
  };

  const requireToken = (req: Request, res: Response, next: NextFunction) => {
    const header = req.headers.authorization ?? '';
    const token = header.toLowerCase().startsWith('bearer ') ? header.slice(7).trim() : '';
    if (!token) return unauthorized(res, false);
    const info = sealer.open<AccessInfo>('access', token);
    if (!info) return unauthorized(res, true);
    res.locals.pat = info.pat;
    next();
  };

  app.post('/mcp', requireToken, express.json({ limit: '8mb' }), async (req, res) => {
    const server = buildServer(api, sealer, cfg.publicUrl, res.locals.pat as string);
    const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
    res.on('close', () => {
      void transport.close();
      void server.close();
    });
    try {
      await server.connect(transport);
      await transport.handleRequest(req, res, req.body);
    } catch {
      if (!res.headersSent) {
        res.status(500).json({ jsonrpc: '2.0', error: { code: -32603, message: 'Erro interno.' }, id: null });
      }
    }
  });

  // Sem sessão: GET (stream de notificações) e DELETE não se aplicam.
  app.all('/mcp', requireToken, (_req, res) => {
    res.status(405).set('Allow', 'POST')
      .json({ jsonrpc: '2.0', error: { code: -32000, message: 'Método não permitido.' }, id: null });
  });

  app.use((_req, res) => { res.status(404).json({ error: 'not_found' }); });
  return app;
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  const cfg = loadConfig();
  createApp(cfg).listen(cfg.port, '0.0.0.0', () => {
    console.log(`ControleFin MCP em :${cfg.port} (público: ${cfg.publicUrl}/mcp)`);
  });
}
