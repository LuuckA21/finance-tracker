# ---- build ----
# The exact Node version is the one for the whole frontend: CI reads it from this line, so tests,
# the end-to-end suite and the production build all run on it. Dependabot proposes patch and minor
# updates here; a new major is chosen by hand.
FROM node:26.10.0-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- runtime: static files + reverse proxy to the API ----
FROM nginxinc/nginx-unprivileged:1.31-alpine
# Files under templates/ go through envsubst at startup and land in /etc/nginx/conf.d/
COPY default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
