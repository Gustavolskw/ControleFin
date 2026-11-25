import type { Response } from 'express';

/** Páginas HTML mínimas (autorização e envio de arquivos). Sem script; CSS próprio; nada de terceiros. */
export const CSP = "default-src 'none'; style-src 'self'; img-src 'self' data:; "
  + "form-action 'self' https://claude.ai https://claude.com http://localhost:* http://127.0.0.1:*; "
  + "frame-ancestors 'none'; base-uri 'none'";

export function esc(value: unknown): string {
  return String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
}

export function page(res: Response, status: number, title: string, body: string): void {
  res.status(status)
    .set('Content-Type', 'text/html; charset=utf-8')
    .set('Content-Security-Policy', CSP)
    .set('X-Frame-Options', 'DENY')
    .set('Cache-Control', 'no-store')
    .set('Referrer-Policy', 'no-referrer')
    .send(`<!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1"><title>${esc(title)} · ControleFin</title>
<link rel="stylesheet" href="/oauth/style.css"></head><body><main class="card">
<div class="brand"><span class="logo">▮▮</span> Controle<b>Fin</b></div>
${body}</main></body></html>`);
}

export const STYLE = `
:root { color-scheme: light dark; --brand: #0F5F56; --bg: #F5F7F6; --surface: #fff; --text: #14201D; --muted: #5B6B66;
  --border: #DCE3E0; --danger: #B42318; --ok: #1E7A4C; }
@media (prefers-color-scheme: dark) { :root { --brand: #138A7B; --bg: #0F1513; --surface: #161E1B; --text: #E8EFEC;
  --muted: #93A49E; --border: #2A3632; --danger: #F28B82; --ok: #6CC79A; } }
* { box-sizing: border-box; }
body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: var(--bg); color: var(--text);
  font: 15px/1.5 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; padding: 16px; }
.card { width: 100%; max-width: 440px; background: var(--surface); border: 1px solid var(--border); border-radius: 14px;
  padding: 24px; box-shadow: 0 6px 24px rgba(0,0,0,.06); }
.brand { font-size: 18px; margin-bottom: 14px; } .brand b { color: var(--brand); } .logo { color: var(--brand); letter-spacing: -2px; }
h1 { font-size: 20px; margin: 0 0 8px; }
p { margin: 0 0 12px; } .muted { color: var(--muted); font-size: 13px; }
label { display: block; font-weight: 600; margin: 12px 0 6px; }
input[type=password], input[type=text], input[type=file] { width: 100%; padding: 10px 12px; border: 1px solid var(--border);
  border-radius: 8px; background: var(--bg); color: var(--text); font: inherit; }
.row { display: flex; gap: 8px; margin-top: 16px; }
button { flex: 1; padding: 10px 14px; border-radius: 8px; border: 1px solid var(--brand); background: var(--brand);
  color: #fff; font: inherit; font-weight: 600; cursor: pointer; }
button.ghost { background: transparent; color: var(--brand); }
.error { background: color-mix(in srgb, var(--danger) 12%, transparent); color: var(--danger); padding: 10px 12px;
  border-radius: 8px; margin-bottom: 12px; }
.ok { background: color-mix(in srgb, var(--ok) 12%, transparent); color: var(--ok); padding: 10px 12px; border-radius: 8px; }
code { font-size: 13px; word-break: break-all; }
ul { padding-left: 18px; margin: 6px 0 12px; }
`;
