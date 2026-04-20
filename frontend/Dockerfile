FROM node:20-alpine AS build
WORKDIR /workspace/frontend

ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY
ARG http_proxy
ARG https_proxy
ARG no_proxy
ARG NPM_CONFIG_REGISTRY
ARG npm_config_registry

ENV HTTP_PROXY=${HTTP_PROXY} \
    HTTPS_PROXY=${HTTPS_PROXY} \
    NO_PROXY=${NO_PROXY} \
    http_proxy=${http_proxy} \
    https_proxy=${https_proxy} \
    no_proxy=${no_proxy} \
    NPM_CONFIG_REGISTRY=${NPM_CONFIG_REGISTRY} \
    npm_config_registry=${npm_config_registry}

COPY frontend/package*.json ./
RUN npm install

COPY frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine
RUN apk add --no-cache gettext

COPY frontend/docker/default.conf /etc/nginx/conf.d/default.conf
COPY frontend/docker/40-runtime-config.sh /docker-entrypoint.d/40-runtime-config.sh
RUN chmod +x /docker-entrypoint.d/40-runtime-config.sh
COPY --from=build /workspace/frontend/dist /usr/share/nginx/html

EXPOSE 80
