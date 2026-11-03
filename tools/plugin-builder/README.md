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
3. **Commit assertion and provenance.** The repo is mounted read-only, and only
   NARROWLY: `plugins-src/`, `tools/`, `config/ops-allowlist.json` and `.git`
   (see "Mounts"). A build/test exports the tree of the job's `ref` directly from
   the object store with `git archive <ref> -- <source>`, unpacks it into a
   disposable directory and builds there. `git rev-parse HEAD` is still asserted
   to equal the job's `ref` (`ref_mismatch` otherwise), so the `ref` is a verified
   claim about which commit was built — and, unlike a working-tree copy, the
   artifact now contains exactly that commit. Nothing but source code is reachable
   from this container: `.env`, `secrets/` and `data/` are not mounted at all.
4. **Diverging working tree is reported, not built.** Local, uncommitted edits
   can no longer leak into an artifact (see above), so they are not an error: the
   result carries `dirty: true|false|null` and `dirty_files: <count>` from
   `git status --porcelain -- <source>` — a COUNT only, never file names. `null`
   means the tree could not be inspected, which is not the same as "clean".
5. **Fixed commands only.** The argv is read from `config/ops-allowlist.json`
   (`build.plugin`, `build.tests.<name>`), never from the job. A spec that names
   an image this container does not declare (`BUILDER_TOOLCHAINS`) is refused
   (`unknown_toolchain`) rather than run on the wrong toolchain, and a spec whose
   `source` is outside the mounted roots (`BUILDER_SOURCE_ROOTS`) is refused with
   `source_not_mounted`.
6. **No network, no socket.** `network_mode: none`, no `/var/run/docker.sock`,
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

## Mounts

| Volume | Mount | Mode |
| --- | --- | --- |
| `mc-stack-ops-jobs` | `/jobs` | rw (job channel) |
| `mc-stack-ops-artifacts` | `/artifacts` | rw (results + jar) |
| `mc-stack-ops-gradle-cache` | `/home/gradle/.gradle` | rw |
| `mc-stack-ops-npm-cache` | `/root/.npm` | rw |
| `./plugins-src` | `/repo/plugins-src` | **ro** |
| `./tools` | `/repo/tools` | **ro** |
| `./config/ops-allowlist.json` | `/repo/config/ops-allowlist.json` | **ro** |
| `./.git` | `/repo/.git` | **ro** |

The repo is mounted NARROWLY on purpose: only the two source trees the allowlist
can build/test, the allowlist file itself and `.git`. The repo **root** is not
mounted, so `.env`, `secrets/` and `data/` are not reachable from this service at
all — a build must never be able to read them (or bake them into a jar that is
then deployed). A compose test asserts exactly that
(`tools/ops-helper/test/proxy.test.mjs`).

`.git` is all `git archive` and `git status` need, and both run with
`GIT_OPTIONAL_LOCKS=0`, so nothing tries to write the index of the read-only
mount. `ops-helper` keeps the whole repo mounted read-only — deliberately, and
for a different reason: see "Why the repo mount is not narrowed" in
`tools/ops-helper/README.md`.

`/work` and `/tmp` are tmpfs (2 GB / default); the tree is exported from git and
built under `/work`, never in `/repo`.

## Image pinning

`FROM gradle:9-jdk@sha256:…` is pinned by **digest**, not tag: a tag is mutable,
so the same commit could otherwise build against a different base image. The
digest is the OCI image **index** digest, so it still resolves per platform.
Refreshing it is a deliberate, reviewable commit (see the comment in the
Dockerfile).

## Tests

```bash
cd tools/plugin-builder && node --test test/*.test.mjs
```

No docker, no network. `test/validate.test.mjs` hammers the job validator with
smuggled commands, unknown keys, bad shas, path traversal in the file name and
oversized bodies. `test/provenance.test.mjs` needs `git` and `tar` (it skips
itself without them): it makes a throwaway repo with a local, uncommitted edit,
puts `.git` in read-only mode like the real mount, and asserts the edit does
**not** reach the exported tree while the tree is reported as `dirty`.
