# VoltStore frontend image (change frontend-deployment, design D1).
# Type check and tests run in CI and locally (npm run check), not in the image.
FROM node:22.22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npx vite build

FROM nginxinc/nginx-unprivileged:1.27.5-alpine
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
