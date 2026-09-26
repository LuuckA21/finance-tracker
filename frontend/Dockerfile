# ---- build ----
FROM node:26-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- runtime: static files + reverse proxy to the API ----
FROM nginxinc/nginx-unprivileged:1.29-alpine
# Files under templates/ go through envsubst at startup and land in /etc/nginx/conf.d/
COPY default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
