import assert from 'node:assert/strict';
import { createHash, randomBytes } from 'node:crypto';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { after, before, describe, it } from 'node:test';
import { createApp } from '../index.js';
import { redirectAllowed, redirectMatches } from '../oauth.js';
import { Sealer } from '../seal.js';

const KEY = 'chave-de-teste-do-mcp-com-mais-de-32-caracteres';
const PAT = 'cf_' + 'A'.repeat(43);
const CALLBACK = 'https://claude.ai/api/mcp/auth_callback';

describe('envelopes cifrados', () => {
  const s = new Sealer(KEY);
  it('abre só com o mesmo propósito e sem alteração', () => {
    const t = s.seal('access', { pat: PAT, exp: Math.floor(Date.now() / 1000) + 60 });
    assert.equal(s.open<{ pat: string }>('access', t)?.pat, PAT);
    assert.equal(s.open('refresh', t), null, 'access token não serve como refresh');
    assert.equal(s.open('upload', t), null);
    const bad = t.slice(0, -2) + (t.endsWith('A') ? 'BB' : 'AA');
    assert.equal(s.open('access', bad), null, 'alterado não abre');
    assert.equal(new Sealer(KEY + 'x').open('access', t), null, 'outra chave não abre');
  });
  it('vencido não abre', () => {
    const t = s.seal('code', { exp: Math.floor(Date.now() / 1000) - 1 });
    assert.equal(s.open('code', t), null);
  });
  it('não vaza o token pessoal no texto', () => {
    const t = s.seal('access', { pat: PAT });
    assert.ok(!Buffer.from(t, 'base64url').toString('latin1').includes('cf_'));
  });
});

describe('retornos (redirect_uri)', () => {
  it('aceita só Claude e loopback', () => {
    assert.ok(redirectAllowed(CALLBACK));
    assert.ok(redirectAllowed('https://claude.com/api/mcp/auth_callback'));
    assert.ok(redirectAllowed('http://localhost:3118/callback'));
    assert.ok(redirectAllowed('http://127.0.0.1/callback'));
    assert.ok(!redirectAllowed('https://evil.example/callback'));
    assert.ok(!redirectAllowed('https://claude.ai.evil.example/api/mcp/auth_callback'));
    assert.ok(!redirectAllowed('http://claude.ai/api/mcp/auth_callback'));
    assert.ok(!redirectAllowed('javascript:alert(1)'));
  });
  it('loopback confere sem a porta', () => {
    assert.ok(redirectMatches(['http://localhost/callback'], 'http://localhost:51234/callback'));
    assert.ok(!redirectMatches(['http://localhost/callback'], 'http://localhost:51234/outra'));
    assert.ok(!redirectMatches([CALLBACK], 'https://claude.com/api/mcp/auth_callback'));
  });
});

describe('fluxo OAuth + MCP', () => {
  let fake: Server;
  let app: Server;
  let base = '';
  let valid = true;
  const calls: { method: string; url: string; auth: string; body: string }[] = [];

  before(async () => {
    fake = createServer((req, res) => {
      let body = '';
      req.on('data', c => { body += c; });
      req.on('end', () => {
        calls.push({ method: req.method!, url: req.url!, auth: req.headers.authorization ?? '', body });
        if (!valid || req.headers.authorization !== `Bearer ${PAT}`) {
          res.writeHead(401, { 'Content-Type': 'application/json' }).end('{"message":"x"}');
          return;
        }
        res.writeHead(req.method === 'POST' ? 201 : 200, { 'Content-Type': 'application/json' });
        if (req.url === '/ext/v1/imports' && req.method === 'POST') {
          res.end(JSON.stringify({ id: '3f1c2a7e-9b8d-4c6a-a1b2-0c9d8e7f6a5b', created: 1, items: [] }));
        } else if (req.url!.startsWith('/ext/v1/transactions/similar')) {
          res.end('[]');
        } else if (req.url!.startsWith('/ext/v1/investments/')) {
          const id = '3f1c2a7e-9b8d-4c6a-a1b2-0c9d8e7f6a5b';
          res.end(JSON.stringify({ investment: { id, name: 'Caixinha', type: 'CAIXINHA', indexer: 'CDI', rate: 100 },
            movements: [{ id, kind: 'APORTE', date: '2026-11-25', amount: 100, walletId: null }] }));
        } else if (req.url!.startsWith('/ext/v1/investments')) {
          res.end(JSON.stringify(req.method === 'GET' ? { currentValue: 0, investments: [] } : { id: 'x', name: 'x' }));
        } else {
          const id = '3f1c2a7e-9b8d-4c6a-a1b2-0c9d8e7f6a5b';
          res.end(JSON.stringify([{ id, name: 'x', walletId: id, cardId: null, wallets: [{ id, name: 'w',
            initialBalance: 0, archived: false }] }]));
        }
      });
    }).listen(0);
    await new Promise(r => fake.once('listening', r));
    const fakeUrl = `http://127.0.0.1:${(fake.address() as AddressInfo).port}/ext/v1`;
    app = createApp({ port: 0, publicUrl: 'https://cf.example', externalApiUrl: fakeUrl, tokenKey: KEY }).listen(0);
    await new Promise(r => app.once('listening', r));
    base = `http://127.0.0.1:${(app.address() as AddressInfo).port}`;
  });
  after(() => { fake.close(); app.close(); });

  const form = (data: Record<string, string>) => new URLSearchParams(data).toString();
  const verifier = randomBytes(32).toString('base64url');
  const challenge = createHash('sha256').update(verifier).digest('base64url');
  let clientId = '';
  let code = '';
  let tokens: { access_token: string; refresh_token: string } = { access_token: '', refresh_token: '' };

  it('MCP sem token responde 401 com o ponteiro de descoberta', async () => {
    const r = await fetch(`${base}/mcp`, { method: 'POST', body: '{}', headers: { 'Content-Type': 'application/json' } });
    assert.equal(r.status, 401);
    assert.match(r.headers.get('www-authenticate') ?? '', /resource_metadata="https:\/\/cf\.example\/\.well-known\/oauth-protected-resource"/);
  });

  it('descoberta (RFC 9728 e RFC 8414)', async () => {
    const pr = await (await fetch(`${base}/.well-known/oauth-protected-resource`)).json();
    assert.equal(pr.resource, 'https://cf.example/mcp');
    assert.deepEqual(pr.authorization_servers, ['https://cf.example']);
    const as = await (await fetch(`${base}/.well-known/oauth-authorization-server`)).json();
    assert.deepEqual(as.code_challenge_methods_supported, ['S256']);
    assert.ok(as.registration_endpoint.endsWith('/oauth/register'));
  });

  it('registro dinâmico recusa retorno que não é do Claude', async () => {
    const bad = await fetch(`${base}/oauth/register`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ redirect_uris: ['https://evil.example/cb'] }) });
    assert.equal(bad.status, 400);
    const ok = await fetch(`${base}/oauth/register`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ redirect_uris: [CALLBACK], client_name: 'Claude' }) });
    assert.equal(ok.status, 201);
    clientId = (await ok.json()).client_id;
  });

  const authParams = () => ({ response_type: 'code', client_id: clientId, redirect_uri: CALLBACK, state: 'xyz',
    code_challenge: challenge, code_challenge_method: 'S256', scope: 'controlefin', resource: 'https://cf.example/mcp' });

  it('tela de autorização pede o token pessoal (sem senha/login)', async () => {
    const r = await fetch(`${base}/oauth/authorize?${form(authParams())}`);
    const html = await r.text();
    assert.equal(r.status, 200);
    assert.match(html, /name="token"/);
    assert.doesNotMatch(html, /name="password"|name="username"/);
    assert.match(r.headers.get('content-security-policy') ?? '', /frame-ancestors 'none'/);
  });

  it('sem PKCE não autoriza', async () => {
    const p = { ...authParams(), code_challenge: '' };
    const r = await fetch(`${base}/oauth/authorize?${form(p)}`, { redirect: 'manual' });
    assert.equal(r.status, 302);
    assert.match(r.headers.get('location') ?? '', /error=invalid_request/);
  });

  it('retorno diferente do registrado não redireciona', async () => {
    const p = { ...authParams(), redirect_uri: 'https://claude.com/api/mcp/auth_callback' };
    const r = await fetch(`${base}/oauth/authorize?${form(p)}`, { redirect: 'manual' });
    assert.equal(r.status, 400);
  });

  it('token pessoal inválido volta ao formulário', async () => {
    const r = await fetch(`${base}/oauth/authorize`, { method: 'POST', redirect: 'manual',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form({ ...authParams(), action: 'allow', token: 'cf_' + 'B'.repeat(43) }) });
    assert.equal(r.status, 400);
    assert.match(await r.text(), /Token inválido/);
  });

  it('token pessoal válido gera código e volta ao Claude com state', async () => {
    const r = await fetch(`${base}/oauth/authorize`, { method: 'POST', redirect: 'manual',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form({ ...authParams(), action: 'allow', token: PAT }) });
    assert.equal(r.status, 302);
    const loc = new URL(r.headers.get('location')!);
    assert.equal(loc.origin + loc.pathname, CALLBACK);
    assert.equal(loc.searchParams.get('state'), 'xyz');
    code = loc.searchParams.get('code')!;
    assert.ok(code && !code.includes('cf_'));
  });

  const token = (data: Record<string, string>) => fetch(`${base}/oauth/token`, { method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: form(data) });

  it('PKCE errado não troca o código', async () => {
    const r = await token({ grant_type: 'authorization_code', code, redirect_uri: CALLBACK, client_id: clientId,
      code_verifier: randomBytes(32).toString('base64url') });
    assert.equal(r.status, 400);
    assert.equal((await r.json()).error, 'invalid_grant');
  });

  it('troca o código (uma vez só)', async () => {
    const r = await token({ grant_type: 'authorization_code', code, redirect_uri: CALLBACK, client_id: clientId,
      code_verifier: verifier });
    assert.equal(r.status, 200);
    tokens = await r.json();
    assert.ok(tokens.access_token && tokens.refresh_token);
    const again = await token({ grant_type: 'authorization_code', code, redirect_uri: CALLBACK, client_id: clientId,
      code_verifier: verifier });
    assert.equal(again.status, 400);
  });

  const rpc = (body: object, access = tokens.access_token) => fetch(`${base}/mcp`, { method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream',
      Authorization: `Bearer ${access}`, 'MCP-Protocol-Version': '2025-11-25' },
    body: JSON.stringify(body) });

  it('MCP: initialize e lista de ferramentas', async () => {
    const init = await rpc({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-11-25',
      capabilities: {}, clientInfo: { name: 'teste', version: '1' } } });
    assert.equal(init.status, 200);
    const ij = await init.json();
    assert.match(ij.result.instructions, /verificar_duplicados/);
    const list = await (await rpc({ jsonrpc: '2.0', id: 2, method: 'tools/list' })).json();
    const names = list.result.tools.map((t: { name: string }) => t.name);
    for (const n of ['obter_contexto', 'verificar_duplicados', 'importar_lancamentos', 'estornar_lancamento']) {
      assert.ok(names.includes(n), n);
    }
    assert.equal(names.length, 48);
    // Regra: nada de login, cadastro, senha, perfil, token ou configuração de usuário.
    for (const n of names) assert.doesNotMatch(n, /login|senha|usuario|perfil|cadastr|token|conta_de_acesso|config/);
  });

  it('MCP: ferramenta chama a API externa com o token pessoal', async () => {
    calls.length = 0;
    const r = await (await rpc({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'verificar_duplicados',
      arguments: { kind: 'DESPESA', date: '2026-11-25', amount: 10.5 } } })).json();
    assert.equal(r.result.isError, undefined);
    assert.equal(calls[0].auth, `Bearer ${PAT}`);
    assert.match(calls[0].url, /\/ext\/v1\/transactions\/similar\?kind=DESPESA&date=2026-11-25&amount=10.5/);
  });

  it('MCP: toda rota da API externa tem ferramenta (paridade)', async () => {
    const id = '3f1c2a7e-9b8d-4c6a-a1b2-0c9d8e7f6a5b';
    const cases: [string, object, string, RegExp][] = [
      ['obter_contexto', {}, 'GET', /\/context$/],
      ['ver_painel', {}, 'GET', /\/dashboard/],
      ['listar_lancamentos', {}, 'GET', /\/transactions$/],
      ['verificar_duplicados', { kind: 'DESPESA', date: '2026-11-25', amount: 1 }, 'GET', /\/transactions\/similar/],
      ['ver_lancamento', { id }, 'GET', new RegExp(`/transactions/${id}$`)],
      ['editar_lancamento', { id, walletId: id }, 'PUT', new RegExp(`/transactions/${id}$`)],
      ['confirmar_lancamento', { id }, 'POST', /\/confirm$/],
      ['estornar_lancamento', { id }, 'POST', /\/reverse$/],
      ['recorrencias_do_mes', {}, 'GET', /\/recurrences\/month/],
      ['listar_faturas', { creditLineId: id }, 'GET', /\/credit-lines\/.+\/invoices$/],
      ['ver_fatura', { invoiceId: id }, 'GET', /\/invoices\/[^/]+$/],
      ['listar_importacoes', {}, 'GET', /\/imports$/],
      ['importar_lancamentos', { items: [{ date: '2026-11-25', amount: 1 }] }, 'POST', /\/imports$/],
      ['ver_importacao', { id }, 'GET', new RegExp(`/imports/${id}$`)],
      ['anexar_texto_a_importacao', { importId: id, files: [{ name: 'a.csv', content: 'data;valor' }] }, 'POST',
        /\/imports\/.+\/attachments$/],
      ['confirmar_importacao', { id }, 'POST', /\/imports\/.+\/confirm$/],
      ['desfazer_importacao', { id }, 'POST', /\/undo$/],
      ['listar_contas', {}, 'GET', /\/accounts$/],
      ['ver_conta', { id }, 'GET', new RegExp(`/accounts/${id}$`)],
      ['criar_conta', { name: 'Nubank', type: 'CORRENTE', holder: 'PF' }, 'POST', /\/accounts$/],
      ['editar_conta', { id, name: 'Nu' }, 'PUT', new RegExp(`/accounts/${id}$`)],
      ['criar_carteira', { accountId: id, name: 'VA' }, 'POST', /\/accounts\/.+\/wallets$/],
      ['editar_carteira', { id, archived: true }, 'PUT', new RegExp(`/wallets/${id}$`)],
      ['ajustar_saldo', { walletId: id, balance: 10 }, 'POST', /\/wallets\/.+\/adjust$/],
      ['listar_categorias', {}, 'GET', /\/categories$/],
      ['criar_categoria', { name: 'Pets', kind: 'DESPESA' }, 'POST', /\/categories$/],
      ['editar_categoria', { id, name: 'Pet' }, 'PUT', new RegExp(`/categories/${id}$`)],
      ['listar_limites', {}, 'GET', /\/credit-lines$/],
      ['ver_limite', { id }, 'GET', new RegExp(`/credit-lines/${id}$`)],
      ['criar_limite', { name: 'Nu', limit: 1000, closingDay: 3, dueDay: 10 }, 'POST', /\/credit-lines$/],
      ['editar_limite', { id, limit: 2000 }, 'PUT', new RegExp(`/credit-lines/${id}$`)],
      ['listar_cartoes', {}, 'GET', /\/cards$/],
      ['criar_cartao', { name: 'Nu virtual', format: 'DIGITAL', function: 'CREDITO_DEBITO', creditLineId: id }, 'POST',
        /\/cards$/],
      ['editar_cartao', { id, archived: true }, 'PUT', new RegExp(`/cards/${id}$`)],
      ['listar_recorrencias', {}, 'GET', /\/recurrences$/],
      ['criar_recorrencia', { kind: 'DESPESA', description: 'Netflix', amount: 40, frequency: 'MENSAL',
        startDate: '2026-12-01', walletId: id }, 'POST', /\/recurrences$/],
      ['editar_recorrencia', { id, amount: 45 }, 'PUT', new RegExp(`/recurrences/${id}$`)],
      ['pular_ocorrencia', { recurrenceId: id, date: '2026-12-01' }, 'POST', /\/occurrences\/2026-12-01\/skip$/],
      ['retomar_ocorrencia', { recurrenceId: id, date: '2026-12-01' }, 'POST', /\/occurrences\/2026-12-01\/unskip$/],
      ['candidatos_da_ocorrencia', { recurrenceId: id, date: '2026-12-01' }, 'GET', /\/candidates$/],
      ['vincular_a_recorrencia', { transactionId: id, recurrenceId: id, recurrenceDate: '2026-12-01' }, 'PUT',
        /\/transactions\/.+\/recurrence$/],
      ['listar_investimentos', {}, 'GET', /\/investments$/],
      ['ver_investimento', { id }, 'GET', new RegExp(`/investments/${id}$`)],
      ['criar_investimento', { name: 'Caixinha', type: 'CAIXINHA', indexer: 'CDI', rate: 100 }, 'POST', /\/investments$/],
      ['editar_investimento', { id, rate: 105 }, 'PUT', new RegExp(`/investments/${id}$`)],
      ['registrar_movimento_investimento', { investmentId: id, kind: 'APORTE', date: '2026-11-25', amount: 100 }, 'POST',
        /\/investments\/.+\/movements$/],
      ['editar_movimento_investimento', { investmentId: id, movementId: id, amount: 150 }, 'PUT',
        /\/investments\/.+\/movements\/.+$/],
    ];
    for (const [name, args, method, url] of cases) {
      calls.length = 0;
      await rpc({ jsonrpc: '2.0', id: 9, method: 'tools/call', params: { name, arguments: args } });
      const hit = calls.find(c => c.method === method && url.test(c.url.split('?')[0]));
      assert.ok(hit, `${name} -> ${method} ${url} (chamou: ${calls.map(c => c.method + ' ' + c.url).join(', ')})`);
    }
  });

  it('MCP: editar trocando para carteira tira o cartão', async () => {
    calls.length = 0;
    const id = '3f1c2a7e-9b8d-4c6a-a1b2-0c9d8e7f6a5b';
    await rpc({ jsonrpc: '2.0', id: 10, method: 'tools/call', params: { name: 'editar_lancamento',
      arguments: { id, walletId: id } } });
    const put = JSON.parse(calls.find(c => c.method === 'PUT')!.body);
    assert.equal(put.walletId, id);
    assert.equal(put.cardId, null);
    assert.equal(put.credit, null);
  });

  it('MCP: editar recorrência trocando para cartão tira a carteira', async () => {
    calls.length = 0;
    const id = '3f1c2a7e-9b8d-4c6a-a1b2-0c9d8e7f6a5b';
    await rpc({ jsonrpc: '2.0', id: 11, method: 'tools/call', params: { name: 'editar_recorrencia',
      arguments: { id, cardId: id, credit: true } } });
    const put = JSON.parse(calls.find(c => c.method === 'PUT')!.body);
    assert.equal(put.cardId, id);
    assert.equal(put.walletId, null);
    assert.equal(put.credit, true);
  });

  it('MCP: importar devolve link de envio que só abre a página do lote', async () => {
    const r = await (await rpc({ jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'importar_lancamentos',
      arguments: { items: [{ date: '2026-11-25', description: 'Padaria', amount: 12 }] } } })).json();
    const data = JSON.parse(r.result.content[0].text);
    assert.ok(data.link_para_anexos.startsWith('https://cf.example/anexos/'));
    const path = new URL(data.link_para_anexos).pathname;
    const pageRes = await fetch(base + path);
    assert.equal(pageRes.status, 200);
    assert.match(await pageRes.text(), /type="file"/);
    // O link não serve como access token.
    const asToken = await rpc({ jsonrpc: '2.0', id: 5, method: 'tools/list' }, path.split('/').pop()!);
    assert.equal(asToken.status, 401);
  });

  it('refresh com rotação: o usado deixa de valer', async () => {
    const r = await token({ grant_type: 'refresh_token', refresh_token: tokens.refresh_token, client_id: clientId });
    assert.equal(r.status, 200);
    const again = await token({ grant_type: 'refresh_token', refresh_token: tokens.refresh_token, client_id: clientId });
    assert.equal(again.status, 400);
    assert.equal((await again.json()).error, 'invalid_grant');
  });

  it('token pessoal revogado: refresh falha com invalid_grant e ferramentas avisam', async () => {
    const fresh = await (await token({ grant_type: 'authorization_code', code: '', redirect_uri: CALLBACK,
      client_id: clientId, code_verifier: verifier })).json();
    assert.equal(fresh.error, 'invalid_grant');
    valid = false;
    const call = await (await rpc({ jsonrpc: '2.0', id: 6, method: 'tools/call', params: { name: 'obter_contexto',
      arguments: {} } })).json();
    assert.equal(call.result.isError, true);
    assert.match(call.result.content[0].text, /revogado/);
    valid = true;
  });
});
