# plugin-builder

The build/test worker behind the ops tools. It exists so that
`ops_build_plugin` / `ops_run_tests` no longer need `POST /containers/create` on
the Docker socket — that route is host root, and `docker-socket-filter` refuses it
on purpose.

```
ops-helper (no socket)                 plugin-builder (no socket, no network)
        │                                        │
        │  job-<uuid>.json                       │  polls /jobs every second
        ▼                                        ▼
   /jobs  (volume mc-stack-ops-jobs)  ───►  validate + run ONE fixed command
                                                 │
                                                 ▼
                            /artifacts/builder/result-<uuid>.json
                                    (volume mc-stack-ops-artifacts)
```

`ops-helper` writes a job file and polls for the answer; this service validates
it, runs exactly one command from `config/ops-allowlist.json` and writes the
result back. The helper never starts a container, and this service never touches
Docker at all.

## What a job looks like

```json
{ "v": 1, "id": "<uuid>", "action": "build", "ref": "<40-hex commit sha>", "requested_at": "…" }
{ "v": 1, "id": "<uuid>", "action": "test",  "ref": "<40-hex commit sha>", "target": "ops-helper", "requested_at": "…" }
```

That is the whole protocol. There is no "run this command" field, no `cwd`, no
`argv`, no environment: the exact key set is checked, so a smuggled key is a
rejected job (`job_bad_keys`).

## Trust model

The job file is written by `ops-helper`, but this service does **not** trust it:

1. **Strict schema.** Only `job-<uuid>.json` is even looked at; the file size is
   capped; the key set must match exactly for the declared action; `v` is pinned;
   the `id` in the body must equal the uuid in the file name.
2. **Action enum and sha shape.** `action` is `build` or `test`; `ref` must be a
   full lower-case 40-hex commit sha; a `test` target must look like a service
   name **and** exist in `build.tests` of the allowlist.
3. **Commit assertion.** The repo is mounted read-only, so nothing can check out
   a different revision here. Instead the builder asserts that
   `git rev-parse HEAD` of the mount equals the job's `ref` (`ref_mismatch`
   otherwise) and then builds the checked-out tree. The `ref` is therefore a
   *claim about which tree was built*, verified, not a revision selector.
4. **Fixed commands only.** The argv is read from `config/ops-allowlist.json`
   (`build.plugin`, `build.tests.<name>`), never from the job. A spec that names
   an image this container does not declare (`BUILDER_TOOLCHAINS`) is refused
   (`unknown_toolchain`) rather than run on the wrong toolchain.
5. **No network, no socket.** `network_mode: none`, no `/var/run/docker.sock`,
   `cap_drop: ALL`, `no-new-privileges`, read-only rootfs and explicit CPU/RAM/PID
   caps. A build cannot reach the internet, the Minecraft servers, the socket or
   the helper.

A compromised `ops-helper` can therefore at most ask this service to build or
test something it was already allowed to build or test.

## Offline by construction (cache warm-up)

The container has no interface at all, so dependencies must come from pre-warmed
volumes. Warm them **once on the host** (that step needs network on the host,
not inside the builder):

```bash
docker compose --profile ops up -d --build plugin-builder   # or:
docker volume create mc-stack-ops-gradle-cache mc-stack-ops-npm-cache

# gradle (craftcore)
docker run --rm -v mc-stack-ops-gradle-cache:/home/gradle/.gradle \
  -v "$PWD/plugins-src/craftcore:/src" -w /src gradle:9-jdk \
  gradle --no-daemon dependencies

# npm (tools/ops-helper, tools/hermes-mcp)
docker run --rm -v mc-stack-ops-npm-cache:/root/.npm \
  -v "$PWD/tools/hermes-mcp:/src" -w /src node:22-alpine \
  npm ci --no-audit --no-fund
```

A cold cache fails loudly: every gradle command carries `--offline` and the npm
setup uses `--offline`.

## Volumes and limits

| Volume | Mount | Mode |
| --- | --- | --- |
| `mc-stack-ops-jobs` | `/jobs` | rw (job channel) |
| `mc-stack-ops-artifacts` | `/artifacts` | rw (results + jar) |
| `mc-stack-ops-gradle-cache` | `/home/gradle/.gradle` | rw |
| `mc-stack-ops-npm-cache` | `/root/.npm` | rw |
| repo | `/repo` | **ro** |

`/work` and `/tmp` are tmpfs (2 GB / default); the build happens on a copy of the
source under `/work`, never in `/repo`.

## Tests

```bash
cd tools/plugin-builder && node --test test/*.test.mjs
```

No docker, no network: the suite hammers the job validator with smuggled
commands, unknown keys, bad shas, path traversal in the file name and oversized
bodies.
