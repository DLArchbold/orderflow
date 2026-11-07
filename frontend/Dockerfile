# Stage 1: build the static files with Vite
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# Stage 2: nginx serves dist and proxies /api to order-service, so the browser still sees one origin (no CORS)
FROM nginx:alpine
# The official nginx image fills ${VARIABLES} in files under /etc/nginx/templates from the environment at start-up
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
ENV ORDER_SERVICE_URL=http://order-service:8080
ENV OIDC_AUTHORITY=http://localhost:8180/realms/orderflow
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
