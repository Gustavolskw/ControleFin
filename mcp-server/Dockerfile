FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json ./
COPY src ./src
# Compila e roda os testes (OAuth, PKCE, MCP): se falharem, a imagem não é gerada.
RUN npm test && npm prune --omit=dev

FROM node:24-alpine
WORKDIR /app
ENV NODE_ENV=production TZ=America/Sao_Paulo
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY package.json ./
# Não roda como root.
USER node
EXPOSE 8090
CMD ["node", "dist/index.js"]
