FROM node:20.18.0-alpine AS build

WORKDIR /workspace
COPY scratch-editor/package.json scratch-editor/package-lock.json ./
COPY scratch-editor/scripts scripts
COPY scratch-editor/src/generated src/generated
COPY scratch-editor/static/microbit static/microbit
RUN --mount=type=cache,target=/root/.npm \
    npm ci

COPY scratch-editor/ ./
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.28.0-alpine

COPY deploy/docker/scratch-editor/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build --chown=nginx:nginx /workspace/build/ /usr/share/nginx/html/
RUN nginx -t

USER nginx
EXPOSE 8080
