# Stage 1: build the static files with Vite. VITE_OIDC_AUTHORITY is baked in at build time (the public Keycloak URL).
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_OIDC_AUTHORITY=http://localhost:8180/realms/orderflow
ENV VITE_OIDC_AUTHORITY=$VITE_OIDC_AUTHORITY
RUN npm run build

# Stage 2: nginx serves dist and proxies /api to order-service, so the browser still sees one origin (no CORS)
FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
