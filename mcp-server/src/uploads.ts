import { Router } from 'express';
import multer from 'multer';
import { ApiError, ExternalApi } from './api.js';
import { esc, page } from './pages.js';
import { Sealer } from './seal.js';

/**
 * Link de envio de arquivos (o Claude não tem os bytes do print/PDF que o usuário mostrou no celular).
 * O link vale 24 h, só para anexar arquivos àquele lote; o arquivo vai para a API externa, que o guarda
 * criptografado e ligado ao lote e aos lançamentos.
 */
interface UploadInfo { pat: string; b: string; exp: number }

const ACCEPT = '.pdf,.png,.jpg,.jpeg,.webp,.heic,.csv,.txt,.ofx,.xls,.xlsx';

export function uploadRouter(sealer: Sealer, api: ExternalApi): Router {
  const router = Router();
  const upload = multer({ storage: multer.memoryStorage(), limits: { fileSize: 10 * 1024 * 1024, files: 10 } });

  const expired = (res: Parameters<typeof page>[0]) => page(res, 410, 'Link vencido',
    '<h1>Link vencido ou inválido</h1><p>Peça ao Claude um novo link para enviar os arquivos.</p>');

  const formHtml = (token: string, message = '') => `
<h1>Enviar arquivos</h1>
<p>Escolha o print, o PDF da fatura ou a planilha de origem. Eles ficam guardados (criptografados) junto com os
lançamentos que o Claude registrou.</p>
${message}
<form method="post" action="/anexos/${esc(token)}" enctype="multipart/form-data">
<label for="files">Arquivos (até 10 MB cada)</label>
<input id="files" name="files" type="file" multiple required accept="${ACCEPT}">
<div class="row"><button type="submit">Enviar</button></div>
</form>
<p class="muted">PDF, PNG, JPG, WEBP, HEIC, CSV, TXT, OFX, XLS ou XLSX.</p>`;

  router.get('/anexos/:token', (req, res) => {
    const info = sealer.open<UploadInfo>('upload', req.params.token);
    if (!info) return expired(res);
    page(res, 200, 'Enviar arquivos', formHtml(req.params.token));
  });

  router.post('/anexos/:token', (req, res) => {
    const info = sealer.open<UploadInfo>('upload', req.params.token);
    if (!info) return expired(res);
    upload.array('files', 10)(req, res, async (err: unknown) => {
      if (err) {
        const msg = err instanceof multer.MulterError && err.code === 'LIMIT_FILE_SIZE'
          ? 'Arquivo maior que 10 MB.' : 'Não foi possível ler os arquivos.';
        return page(res, 400, 'Enviar arquivos', formHtml(req.params.token, `<div class="error">${esc(msg)}</div>`));
      }
      const files = (req.files as Express.Multer.File[] | undefined) ?? [];
      if (files.length === 0) {
        return page(res, 400, 'Enviar arquivos', formHtml(req.params.token, '<div class="error">Escolha um arquivo.</div>'));
      }
      try {
        await api.request(info.pat, 'POST', `/imports/${info.b}/attachments`, {
          attachments: files.map(f => ({
            name: Buffer.from(f.originalname, 'latin1').toString('utf8'),
            mimeType: f.mimetype,
            contentBase64: f.buffer.toString('base64'),
          })),
        });
        page(res, 200, 'Arquivos enviados', `<h1>Pronto!</h1><div class="ok">${files.length} arquivo(s) guardado(s):</div>
<ul>${files.map(f => `<li>${esc(Buffer.from(f.originalname, 'latin1').toString('utf8'))}</li>`).join('')}</ul>
<p class="muted">Você pode fechar esta página e voltar ao Claude. Os arquivos aparecem em Importações e nos lançamentos.</p>`);
      } catch (e) {
        const msg = e instanceof ApiError ? e.message : 'Erro ao enviar.';
        page(res, 400, 'Enviar arquivos', formHtml(req.params.token, `<div class="error">${esc(msg)}</div>`));
      }
    });
  });

  return router;
}
