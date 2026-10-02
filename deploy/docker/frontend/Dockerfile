FROM node:24.11.0-alpine AS build

WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci

COPY frontend/ ./
RUN npm run generate:api \
    && npm run build

FROM nginxinc/nginx-unprivileged:1.28.0-alpine

COPY deploy/docker/frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=nginx:nginx /workspace/frontend/dist/ /usr/share/nginx/html/
RUN nginx -t

USER nginx
EXPOSE 8080
