# syntax=docker/dockerfile:1

# ---- build ----
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

# ---- runtime ----
FROM node:22-alpine
# The Docker CLI and git are the only binaries this service may ever run (fixed
# argv lists, never a shell string). Installed at build time; the running
# container needs no internet of its own.
RUN apk add --no-cache docker-cli git
ENV NODE_ENV=production \
    OPS_HELPER_PORT=8766 \
    OPS_HELPER_HOST=0.0.0.0
WORKDIR /app
# Zero runtime dependencies: only the compiled sources and the manifest.
COPY --from=build /app/dist ./dist
COPY package.json ./package.json
# Runs as root ON PURPOSE: it holds the Docker socket (root-equivalent anyway),
# and it must be able to hand the shared artifact volume to build containers that
# run as uid 1000 (gradle/node images). read_only rootfs, cap_drop ALL,
# no-new-privileges and a tmpfs /tmp still apply - see README.
USER root
EXPOSE 8766
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.OPS_HELPER_PORT||8766)+'/healthz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
ENTRYPOINT ["node", "dist/index.js"]
