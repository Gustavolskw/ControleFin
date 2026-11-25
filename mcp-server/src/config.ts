/** Configuração vinda do ambiente (docker-compose / .env). */
export interface Config {
  port: number;
  /** URL pública do sistema (ex.: https://controlefin.gkodex.cloud). O MCP fica em <publicUrl>/mcp. */
  publicUrl: string;
  /** API externa na rede interna do Docker (o MCP só fala com ela). */
  externalApiUrl: string;
  /** Chave que cifra códigos, tokens OAuth e links de envio (mínimo 32 caracteres). */
  tokenKey: string;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const tokenKey = env.MCP_TOKEN_KEY ?? '';
  if (tokenKey.length < 32) throw new Error('MCP_TOKEN_KEY precisa ter pelo menos 32 caracteres.');
  const publicUrl = (env.PUBLIC_URL ?? 'http://localhost:8091').replace(/\/+$/, '');
  return {
    port: Number(env.PORT ?? 8090),
    publicUrl,
    externalApiUrl: (env.EXTERNAL_API_URL ?? 'http://external-api:8081/ext/v1').replace(/\/+$/, ''),
    tokenKey,
  };
}
