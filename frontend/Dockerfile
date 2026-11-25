# Multi-stage build: compiles React + TypeScript bundle, then serves it via Nginx
# which also reverse-proxies /api and /ws across backend-1 and backend-2.
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json ./
RUN npm install

COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
