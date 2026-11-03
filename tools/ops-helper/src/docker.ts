/**
 * Docker argv builders and container resolution.
 *
 * Every function returns an argv list (never a shell string) and every value
 * that ends up in it is either a constant, a 12+ hex container id that came
 * back from `docker ps`, or a service name that was checked against the
 * allowlist enum first. Nothing else is interpolated.
 */
import type { Allowlist } from "./allowlist.js";
import type { Runner } from "./runner.js";

export const ID_RE = /^[0-9a-f]{12,64}$/;
const SERVICE_RE = /^[a-z0-9][a-z0-9-]{0,31}$/;

export interface ContainerState {
  id: string;
  status: string;
  running: boolean;
  health: string | null;
  startedAt: string | null;
}

export class DockerError extends Error {
  constructor(
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

/** `docker ps` filtered by the compose project label. */
export function psArgv(project: string, service?: string): string[] {
  const argv = [
    "ps",
    "-a",
    "--filter",
    `label=com.docker.compose.project=${project}`,
  ];
  if (service) argv.push("--filter", `label=com.docker.compose.service=${service}`);
  argv.push("--format", "{{json .}}");
  return argv;
}

/** Ids of the containers of one compose service (must be exactly one). */
export function resolveServiceArgv(project: string, service: string): string[] {
  return [
    "ps",
    "-a",
    "--filter",
    `label=com.docker.compose.project=${project}`,
    "--filter",
    `label=com.docker.compose.service=${service}`,
    "--format",
    "{{.ID}}",
  ];
}

export function inspectStateArgv(id: string): string[] {
  return ["inspect", "--format", "{{json .State}}", id];
}

export function logsArgv(id: string, tail: number): string[] {
  return ["logs", "--tail", String(tail), "--timestamps", id];
}

export function restartArgv(id: string): string[] {
  return ["restart", id];
}

export function execArgv(id: string, command: string[]): string[] {
  return ["exec", id, ...command];
}

export interface RunContainerOptions {
  image: string;
  command: string[];
  /** `<volume-or-dir>:<container-path>` bindings. */
  volumes: string[];
  workdir?: string;
  env?: Record<string, string>;
  /**
   * Docker network for the throwaway container. Defaults to `none`: a build or
   * test container gets no interface at all, so it cannot reach the internet,
   * the mc-stack services or the socket proxy. Offline caches (pre-warmed
   * volumes) are how dependencies are available without a network.
   */
  network?: string;
  /** `docker --cpus` value, e.g. "1.0". */
  cpus?: string;
  /** `docker --memory` value, e.g. "1536m". */
  memory?: string;
  /** `docker --pids-limit` value. */
  pidsLimit?: number;
}

/**
 * A throwaway container: `--rm`, no capabilities, no privilege escalation, no
 * host network/pid/ipc, `--network none` by default and hard CPU/RAM/PID caps so
 * a build can never starve the Minecraft servers. Only the volumes we name are
 * visible to it.
 */
export function runContainerArgv(opts: RunContainerOptions): string[] {
  const argv = [
    "run",
    "--rm",
    "--cap-drop",
    "ALL",
    "--security-opt",
    "no-new-privileges",
    "--network",
    opts.network ?? "none",
  ];
  if (opts.cpus) argv.push("--cpus", opts.cpus);
  if (opts.memory) argv.push("--memory", opts.memory);
  if (opts.pidsLimit) argv.push("--pids-limit", String(opts.pidsLimit));
  for (const [key, value] of Object.entries(opts.env ?? {})) {
    argv.push("--env", `${key}=${value}`);
  }
  for (const volume of opts.volumes) argv.push("--volume", volume);
  if (opts.workdir) argv.push("--workdir", opts.workdir);
  argv.push(opts.image, ...opts.command);
  return argv;
}

/** True when a service name is safe to use in a filter argument. */
export function isServiceName(service: unknown): service is string {
  return typeof service === "string" && SERVICE_RE.test(service);
}

/** Service names that carry a healthcheck / rcon-cli in this stack. */
export function parseContainerId(output: string): string {
  const ids = output
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean);
  return ids.length === 1 ? ids[0] : "";
}

export class DockerCli {
  constructor(
    private readonly runner: Runner,
    private readonly bin: string,
    private readonly project: string,
    private readonly timeoutMs: number,
  ) {}

  private async run(argv: string[], timeoutMs = this.timeoutMs) {
    return await this.runner.run([this.bin, ...argv], { timeoutMs });
  }

  /** Container ids of a compose service; throws when it is not exactly one. */
  async containerId(service: string): Promise<string> {
    if (!isServiceName(service)) throw new DockerError("invalid_service", `invalid service name: ${service}`);
    const res = await this.run(resolveServiceArgv(this.project, service));
    if (!res.ok) {
      throw new DockerError(
        "docker_failed",
        `docker ps failed for ${service}: ${(res.stderr || res.stdout || res.error || "unknown error").trim().slice(0, 300)}`,
      );
    }
    const id = parseContainerId(res.stdout);
    if (!id) {
      throw new DockerError("service_not_found", `no running or stopped container found for service ${service}`);
    }
    if (!ID_RE.test(id)) {
      throw new DockerError("unexpected_docker_output", `docker ps returned an unexpected id for ${service}`);
    }
    return id;
  }

  async ps(service?: string): Promise<Array<Record<string, unknown>>> {
    const res = await this.run(psArgv(this.project, service));
    if (!res.ok) {
      throw new DockerError("docker_failed", `docker ps failed: ${(res.stderr || res.error || "").trim().slice(0, 300)}`);
    }
    return res.stdout
      .split("\n")
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => {
        try {
          return JSON.parse(line) as Record<string, unknown>;
        } catch {
          return {};
        }
      });
  }

  async state(id: string): Promise<ContainerState> {
    if (!ID_RE.test(id)) throw new DockerError("invalid_id", "refusing to inspect an unexpected container id");
    const res = await this.run(inspectStateArgv(id));
    if (!res.ok) {
      throw new DockerError("docker_failed", `docker inspect failed: ${(res.stderr || res.error || "").trim().slice(0, 300)}`);
    }
    let parsed: Record<string, unknown> = {};
    try {
      parsed = JSON.parse(res.stdout.trim()) as Record<string, unknown>;
    } catch {
      throw new DockerError("unexpected_docker_output", "docker inspect returned unparseable JSON");
    }
    const health = parsed.Health as { Status?: string } | undefined;
    const status = typeof parsed.Status === "string" ? parsed.Status : "unknown";
    return {
      id,
      status,
      running: status === "running",
      health: health && typeof health.Status === "string" ? health.Status : null,
      startedAt: typeof parsed.StartedAt === "string" ? parsed.StartedAt : null,
    };
  }

  async logs(id: string, tail: number): Promise<{ text: string; truncated: boolean }> {
    if (!ID_RE.test(id)) throw new DockerError("invalid_id", "refusing to read logs of an unexpected container id");
    const res = await this.run(logsArgv(id, tail));
    // `docker logs` writes the container's stderr to our stderr; both are part of
    // the answer, so they are merged (stdout first, then stderr).
    const text = [res.stdout, res.stderr].filter(Boolean).join("\n");
    return { text, truncated: res.truncated };
  }

  async restart(id: string, timeoutMs?: number): Promise<void> {
    if (!ID_RE.test(id)) throw new DockerError("invalid_id", "refusing to restart an unexpected container id");
    const res = await this.run(restartArgv(id), timeoutMs);
    if (!res.ok) {
      throw new DockerError("restart_failed", `docker restart failed: ${(res.stderr || res.error || "").trim().slice(0, 300)}`);
    }
  }

  async exec(id: string, command: string[]): Promise<{ ok: boolean; text: string }> {
    if (!ID_RE.test(id)) throw new DockerError("invalid_id", "refusing to exec in an unexpected container id");
    const res = await this.run(execArgv(id, command));
    return { ok: res.ok, text: [res.stdout, res.stderr].filter(Boolean).join("\n").trim() };
  }

  async runContainer(opts: RunContainerOptions, timeoutMs: number) {
    return await this.run(runContainerArgv(opts), timeoutMs);
  }
}

/** Resolve the plugin directory of a service from the allowlist (no traversal). */
export function pluginDir(allow: Allowlist, service: string): string {
  const dir = allow.deploy.pluginDirs[service];
  if (!dir || !dir.startsWith("/") || dir.split("/").includes("..")) {
    throw new DockerError("invalid_plugin_dir", `no safe plugin directory configured for ${service}`);
  }
  return dir;
}
