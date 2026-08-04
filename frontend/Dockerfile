FROM node:24-alpine AS builder
WORKDIR /app
ARG VITE_PUBLIC_SITE_URL
ENV VITE_PUBLIC_SITE_URL=$VITE_PUBLIC_SITE_URL
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.29-alpine
COPY nginx.conf /etc/nginx/nginx.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
