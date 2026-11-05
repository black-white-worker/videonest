FROM node:24-alpine AS builder
WORKDIR /app
ARG VITE_PUBLIC_SITE_URL
ENV VITE_PUBLIC_SITE_URL=$VITE_PUBLIC_SITE_URL
COPY package*.json ./
# npmmirror 源 + npm 缓存挂载
RUN --mount=type=cache,target=/root/.npm \
    npm config set registry https://registry.npmmirror.com && npm ci
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/nginx.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
