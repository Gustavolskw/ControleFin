import { createCipheriv, createDecipheriv, createHash, randomBytes, timingSafeEqual } from 'node:crypto';

/**
 * "Envelopes" cifrados com AES-256-GCM: client_id, código de autorização, access/refresh token e link de envio
 * são opacos para quem os recebe e não podem ser alterados. O propósito entra como dado autenticado (AAD): um
 * refresh token não serve como access token, um link de envio não serve como token, etc.
 *
 * Nada é gravado em banco: o servidor MCP não tem estado (o token pessoal do usuário vai dentro do envelope e o
 * backend continua validando-o em toda chamada — revogar o token no site corta o acesso).
 */
export type Purpose = 'client' | 'code' | 'access' | 'refresh' | 'upload';

export class Sealer {
  private readonly key: Buffer;

  constructor(secret: string) {
    this.key = createHash('sha256').update(secret, 'utf8').digest();
  }

  seal(purpose: Purpose, payload: object): string {
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.key, iv);
    cipher.setAAD(Buffer.from(purpose));
    const ct = Buffer.concat([cipher.update(JSON.stringify(payload), 'utf8'), cipher.final()]);
    return Buffer.concat([iv, ct, cipher.getAuthTag()]).toString('base64url');
  }

  /** Abre o envelope; null se for de outro propósito, alterado, inválido ou vencido (campo exp, em segundos). */
  open<T extends object>(purpose: Purpose, token: string | undefined | null): T | null {
    if (!token || token.length > 4096 || !/^[A-Za-z0-9_-]+$/.test(token)) return null;
    try {
      const raw = Buffer.from(token, 'base64url');
      if (raw.length < 12 + 16 + 2) return null;
      const iv = raw.subarray(0, 12);
      const tag = raw.subarray(raw.length - 16);
      const decipher = createDecipheriv('aes-256-gcm', this.key, iv);
      decipher.setAAD(Buffer.from(purpose));
      decipher.setAuthTag(tag);
      const plain = Buffer.concat([decipher.update(raw.subarray(12, raw.length - 16)), decipher.final()]);
      const value = JSON.parse(plain.toString('utf8')) as T & { exp?: unknown };
      if (typeof value.exp === 'number' && value.exp < now()) return null;
      return value;
    } catch {
      return null;
    }
  }
}

export function now(): number {
  return Math.floor(Date.now() / 1000);
}

export function sha256b64url(value: string): string {
  return createHash('sha256').update(value, 'utf8').digest('base64url');
}

export function safeEqual(a: string, b: string): boolean {
  const x = Buffer.from(a), y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
}

/** Lembra ids de uso único (códigos e refresh tokens já usados) até vencerem. Por instância. */
export class UsedIds {
  private readonly ids = new Map<string, number>();

  /** true se o id ainda não tinha sido usado (e marca como usado). */
  use(id: string, exp: number): boolean {
    this.cleanup();
    if (this.ids.has(id)) return false;
    this.ids.set(id, exp);
    return true;
  }

  private cleanup(): void {
    const t = now();
    for (const [k, exp] of this.ids) if (exp < t) this.ids.delete(k);
  }
}
