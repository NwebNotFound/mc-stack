# syntax=docker/dockerfile:1

# plugin-builder: the build/test worker for the ops tools.
#
# It is a PERMANENT service on purpose. `ops_build_plugin` / `ops_run_tests` used
# to start a throwaway container, which needs `POST /containers/create` on the
# Docker socket - and docker-socket-filter refuses that route, because create is
# host root. This image therefore has NO Docker socket and the compose service
# runs with `network_mode: none`: it only ever reads job files from a shared
# volume and writes results back into the artifact volume.
#
# Both toolchains live in one image (gradle for craftcore, node for the JS test
# suites) so a single service can serve every fixed command in
# config/ops-allowlist.json. Dependencies come from pre-warmed cache volumes
# (see README), never from the network.
FROM gradle:9-jdk

USER root
# git is only used for one read-only assertion (`git rev-parse HEAD` against the
# read-only repo mount); node runs the poller and the JS test suites.
RUN apt-get update \
  && apt-get install -y --no-install-recommends nodejs git ca-certificates \
  && rm -rf /var/lib/apt/lists/*

ENV BUILDER_JOBS_DIR=/jobs \
    BUILDER_ARTIFACTS_DIR=/artifacts \
    BUILDER_RESULTS_DIR=/artifacts/builder \
    BUILDER_REPO=/repo \
    BUILDER_ALLOWLIST_PATH=/repo/config/ops-allowlist.json \
    BUILDER_WORK_DIR=/work \
    BUILDER_POLL_MS=1000 \
    BUILDER_JOB_TIMEOUT_SECONDS=1800 \
    BUILDER_TOOLCHAINS=gradle:9-jdk,node:22-alpine \
    GRADLE_USER_HOME=/home/gradle/.gradle

WORKDIR /app
COPY package.json ./package.json
COPY src ./src

# Runs as root so it can own the shared job/artifact volumes and the gradle/npm
# cache volumes. It holds NO capabilities (cap_drop ALL), cannot gain any
# (no-new-privileges), has a read-only rootfs and no network at all, so that is
# not host root.
USER root
ENTRYPOINT ["node", "src/poller.mjs"]
