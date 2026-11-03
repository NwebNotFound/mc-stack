# syntax=docker/dockerfile:1

# The privilege boundary of the whole ops stack. It is deliberately tiny:
# node + two source files, ZERO npm dependencies (nothing to audit, nothing to
# update, no lockfile drift).
FROM node:22-alpine

ENV NODE_ENV=production \
    OPS_FILTER_PORT=2375 \
    OPS_FILTER_HOST=0.0.0.0

WORKDIR /app
COPY package.json ./package.json
COPY src ./src

# Runs as root only because /var/run/docker.sock is typically root:docker 0660.
# read_only rootfs, cap_drop ALL and no-new-privileges still apply (see compose).
USER root
EXPOSE 2375

# Real end-to-end probe: it goes through the filter to the daemon's /_ping.
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.OPS_FILTER_PORT||2375)+'/_ping',{method:'HEAD'}).then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

ENTRYPOINT ["node", "src/server.mjs"]
