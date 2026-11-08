FROM node:20-alpine AS builder

WORKDIR /app
COPY timer-backend/package*.json timer-backend/tsconfig.json ./
RUN npm install
COPY timer-backend/src/ ./src/
RUN npm run build

FROM node:20-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
COPY timer-backend/package*.json ./
RUN npm install --omit=dev
COPY --from=builder /app/dist ./dist

EXPOSE 10000
CMD ["node", "dist/index.js"]
