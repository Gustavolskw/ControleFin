# Primeira instalação na VPS

Passo a passo para colocar o ControleFin no ar em **https://controlefin.gkodex.cloud**, na mesma VPS e atrás do mesmo
Traefik do Chama&Essencia. Leva uns 15 minutos; a maior parte é o primeiro build.

## 0. O que a VPS precisa ter

Conecte na VPS (`ssh usuario@72.61.51.150`) e confira:

```bash
docker --version && docker compose version    # Docker com o plugin Compose
git --version && openssl version
docker network ls | grep proxy                 # rede do Traefik (a mesma do Chama&Essencia)
free -h                                        # memória: o build pede ~2 GB livres (RAM + swap)
```

- **Sem a rede `proxy`:** veja o nome da rede do Traefik com `docker network ls` e anote. Você vai ajustar
  `TRAEFIK_NETWORK` no passo 3.
- **Pouca memória:** o build do Java e do Angular pode ser interrompido. Crie 2 GB de swap:

  ```bash
  sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
  ```

O DNS já está pronto: `controlefin.gkodex.cloud` aponta para `72.61.51.150`.

## 1. Baixar o código

Coloque a pasta ao lado da do Chama&Essencia:

```bash
cd ~
git clone https://github.com/Gustavolskw/ControleFin.git controlefin
cd controlefin
chmod +x controlefin.sh
```

Se o repositório for **privado**, o `git clone` pede usuário e senha. No GitHub, a senha é um token: crie em
**Settings → Developer settings → Personal access tokens → Fine-grained**, só com leitura (*Contents: Read*) deste
repositório, e cole no lugar da senha. Outra opção é uma *deploy key* SSH.

## 2. Gerar o `.env` (senhas e chaves)

```bash
./controlefin.sh init
```

Ele cria o `.env` com todas as senhas e chaves aleatórias e **mostra a senha inicial do administrador**. Anote:

```
✔ Primeiro acesso: usuário controlefin, senha inicial Cf@xxxxxxxxxxxxxxxx (troque no primeiro login)
```

Se esquecer, a senha fica no `.env`, em `DEFAULT_USER_PASSWORD`.

## 3. Conferir a publicação pelo Traefik

```bash
grep -E '^(TRAEFIK_|APP_DOMAIN)' .env
```

O esperado é:

```
TRAEFIK_ENABLED=true          # o init liga sozinho quando encontra a rede "proxy"
APP_DOMAIN=controlefin.gkodex.cloud
TRAEFIK_NETWORK=proxy
TRAEFIK_CERTRESOLVER=letsencrypt
TRAEFIK_ENTRYPOINT_HTTPS=websecure
TRAEFIK_ENTRYPOINT_HTTP=web
```

Os três últimos precisam ser **iguais aos do Chama&Essencia**. Compare:

```bash
grep -E '^TRAEFIK_' ~/<pasta-do-chama-essencia>/.env
```

Se algo for diferente, edite com `nano .env`.

## 4. Guardar uma cópia do `.env` fora da VPS

Sem o `.env`, os backups e os anexos **não abrem**. Do seu computador:

```bash
scp usuario@72.61.51.150:~/controlefin/.env ./controlefin.env
```

Guarde num gerenciador de senhas ou num lugar seguro, e apague a cópia solta depois.

## 5. Subir

```bash
./controlefin.sh start
```

O primeiro build leva de 5 a 10 minutos. No fim aparece:

```
✔ ControleFin no ar: https://controlefin.gkodex.cloud
```

Na primeira visita o Traefik emite o certificado HTTPS do Let's Encrypt; isso leva alguns segundos.

## 6. Conferir

```bash
./controlefin.sh status                                                       # todos "running"/"healthy"
curl -sI https://controlefin.gkodex.cloud | head -1                           # HTTP/2 200
curl -s https://controlefin.gkodex.cloud/.well-known/oauth-protected-resource # JSON do conector do Claude
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://controlefin.gkodex.cloud/mcp   # 401 = protegido
```

## 7. Primeiro acesso no site

1. Abra **https://controlefin.gkodex.cloud** e entre com `controlefin` e a senha inicial.
2. O sistema obriga a **trocar a senha** e a cadastrar as **3 perguntas de segurança**. Elas servem para recuperar a
   senha; não há e-mail.
3. **Usuário pessoal (recomendado):** em **Administração → Convites**, gere um código e cadastre o seu usuário pela
   tela de cadastro, com o código. Assim o administrador fica só para administrar.
4. **Cadastros, no seu usuário:**
   - contas e carteiras (com o saldo de hoje);
   - cartões e limites de crédito (fechamento e vencimento);
   - categorias;
   - recorrências (assinaturas, aluguel, salário).

## 8. Conectar o Claude

1. No site: **Meu perfil → Acesso do Claude → Criar token** ("Ler e lançar"). Copie o token: ele aparece uma vez só.
2. No **claude.ai** pelo navegador: **Configurações → Conectores → Adicionar conector personalizado**.
   - **URL:** `https://controlefin.gkodex.cloud/mcp`
   - Deixe os campos de OAuth **vazios**.
3. **Conectar** → cole o token → **Autorizar**.
4. Pronto: vale no navegador, no app do celular e no Claude Desktop da mesma conta. Teste com "qual o meu saldo
   total?".

Detalhes em [`MCP.md`](MCP.md).

## 9. Backup diário

```bash
crontab -e
```

Acrescente esta linha (backup às 3 h):

```
0 3 * * * cd ~/controlefin && ./controlefin.sh backup >> backups/cron.log 2>&1
```

Os backups (banco e anexos, criptografados) ficam em `~/controlefin/backups/`. De vez em quando, copie alguns para fora
da VPS.

## Atualizar depois

```bash
cd ~/controlefin && ./controlefin.sh update
```

Ele faz backup, depois `git pull`, depois rebuild. As migrações do banco rodam sozinhas.

## Se algo der errado

| Sintoma | O que fazer |
|---|---|
| `Rede do Traefik 'proxy' não existe` | `docker network ls`, e ajuste `TRAEFIK_NETWORK` no `.env` |
| Navegador mostra **404** do Traefik | O ControleFin não entrou na rede do Traefik. Confira `TRAEFIK_ENABLED=true` e rode `./controlefin.sh restart` |
| Certificado inválido ou "não seguro" | Confira se `TRAEFIK_CERTRESOLVER` é o mesmo do Chama&Essencia. Com Cloudflare, desligue o proxy (nuvem cinza) até emitir. Veja os logs do Traefik: `docker logs <container-do-traefik> 2>&1 \| grep -i controlefin` |
| Build para no meio (`Killed`, código 137) | Falta memória: crie o swap do passo 0 e rode `./controlefin.sh start` de novo |
| `A API não respondeu pelo gateway` | `./controlefin.sh logs backend` (o primeiro start do banco pode demorar) |
| O Claude não conecta | Teste os `curl` do passo 6. Os prints e o erro mostrados pelo Claude ajudam a descobrir a causa |
