#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
command -v docker >/dev/null 2>&1 || { echo 'Docker fehlt. Docker und Docker Compose installieren.' >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo 'Docker ist nicht erreichbar. Docker starten und erneut versuchen.' >&2; exit 1; }
docker compose version >/dev/null 2>&1 || { echo 'Docker Compose fehlt.' >&2; exit 1; }
umask 077
mkdir -p data/lobby data/survival data/velocity secrets
new_secret() { od -An -N32 -tx1 /dev/urandom | tr -d ' \n'; }
if [ ! -e .env ]; then
    secret=$(new_secret)
    sed "s/^RCON_PASSWORD=.*/RCON_PASSWORD=$secret/" .env.example > .env
fi
if [ ! -e secrets/forwarding.secret ]; then
    new_secret > secrets/forwarding.secret
fi
docker compose up -d
echo 'Start angefordert. Der erste Start kann einige Minuten dauern: docker compose ps'
