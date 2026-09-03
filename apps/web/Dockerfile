# Stage 1: Build the static bundle
FROM node:22-alpine AS build
WORKDIR /build

# Copy manifests first so the dependency layer is cached until they actually change.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
# Baked in at build time: Vite inlines VITE_* values into the bundle, so a split-origin
# deployment must supply them here rather than at container start.
ARG VITE_API_BASE_URL=""
ARG VITE_WS_URL=""
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_WS_URL=$VITE_WS_URL
RUN npm run build

# Stage 2: Serve
FROM nginx:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html

# Into templates/, not conf.d/: the official nginx entrypoint runs envsubst over
# /etc/nginx/templates/*.template at startup, which is what expands ORDEROPS_API_HOST.
# Dropped straight into conf.d/ the ${...} would reach nginx literally and fail to parse.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
ENV ORDEROPS_API_HOST=orderops-api:8080

EXPOSE 80
