# Static Vue 3 image; build with main/manager-web as the Docker context.
FROM node:22.23.1-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY index.html vite.config.js ./
COPY src ./src
COPY public ./public
COPY scripts ./scripts
ARG VITE_API_BASE_URL=/xiaozhi
ARG VITE_CONVERSATION_ENABLED=true
ARG VITE_ENV_LABEL="Dev Vue 3"
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY deploy/nginx.conf.template /etc/nginx/templates/default.conf.template
ENV API_PROXY_TARGET=http://api:8002
ENV NGINX_ENVSUBST_FILTER=^API_PROXY_TARGET$
EXPOSE 8080
