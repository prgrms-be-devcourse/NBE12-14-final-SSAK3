# --- build ---
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
# package-lock.json 을 커밋한 뒤에는 npm ci 로 바꾸세요
RUN npm install
COPY . .
RUN npm run build

# --- serve (정적 파일 + /api, /ws 프록시) ---
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
