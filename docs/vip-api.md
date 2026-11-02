# VIP management API

Contract for the website (Cloud). The game stack serves these routes. The website calls this document, not the internal lobby routes.

Base URL is the existing host service (`tools/pvp-host-service`, `PVP_HOST_BIND` / `PVP_HOST_PORT`, default `127.0.0.1:25599`). Publish it only through the existing Cloudflare Tunnel on a dedicated hostname such as `vip-api.<domain>`, with Cloudflare Access in front. The browser never sees the tokens. A server-side worker calls the tunnel.

`Content-Type` of every JSON body is `application/json; charset=utf-8`. Clients branch on HTTP status and `error.code`. `error.message` is German display text and can change.

## Auth

Every `/api/v1/vip` route requires both headers:

| Header | Value |
|---|---|
| `X-VIP-Api-Token` | `VIP_API_TOKEN` from the host environment. Distinct from `VIP_HOST_TOKEN` / `X-VIP-Token`. |
| `X-Discord-Id` | Discord snowflake of the logged-in user, `^[0-9]{1,20}$`. |

The edge also requires the Cloudflare Access service token (`CF-Access-Client-Id`, `CF-Access-Client-Secret`). The origin checks the two headers above. A missing or wrong API token is `401`. A missing or malformed Discord id is `401`.

The origin resolves `X-Discord-Id` through `craftcore.discord_links.discord_id` to a Minecraft UUID. No row, or a null UUID, is `403 not_linked`. Rank, slot count, and player cap are computed on the server from LuckPerms and `config/vipworlds.yml`. Body fields named `rank`, `slots`, `playerLimit`, or `admin` are ignored.

When `VIP_API_TOKEN` or `CRAFTCORE_DB_HOST` is unset, every management route returns `503 api_disabled`. The internal lobby routes keep working.

Limits, per Discord id: 30 requests per minute on reads, 5 per minute on `POST /worlds/{id}/start` and `POST /worlds/{id}/stop`. Over the limit: `429` and `Retry-After` in seconds.

## Ranks and limits

The server returns the numbers. The website displays them.

Resolved from the player's primary LuckPerms group and meta `vipworlds.slots` (fallback meta key `vipworld-slots`, which the rank setup script still writes):

| Who | World slots | Players in one running world |
|---|---|---|
| `vip` | meta, normally 1 | 5 |
| `mvp` (meta slots >= 3) | meta, normally 3 | 10 |
| `admin` (`group.admin` or `craftcore.admin`) | max(meta, `admin-slots` from config, default 3) | 10 |
| anyone else | 0 | 0 |

`admin-slots`, `players.vip`, `players.mvp`, and `players.admin` live in `config/vipworlds.yml`. RAM stays in the `ram` block of that file. A start is refused with `503 ram_budget` when the host budget check fails. Survival's reserved RAM is unchanged.

A new world stores `playerLimit` from the owner's rank at create time. Later rank changes do not shrink a world that is already full until the next successful start, which refreshes the stored cap. Admins join over the cap. The owner joins over the cap. Everyone else is kicked at login when `online >= playerLimit`.

## Status values

`stopped`, `starting`, `running`, `stopping`, `error`. `queued` is reserved and is not returned by this rework. `deleted` worlds are omitted.

Slot status on the runtime endpoint uses the internal vocabulary: `online`, `starting`, `sleeping`, `offline`.

## Errors

```json
{ "error": { "code": "no_free_slot", "message": "Alle Slots belegt." } }
```

| HTTP | code | When |
|---|---|---|
| 400 | `invalid_json` | Body is missing or not a JSON object |
| 400 | `invalid_name` | Name is not 3–24 of `[A-Za-z0-9_-]` |
| 400 | `invalid_type` | Type is not `normal`, `flat`, or `void` |
| 400 | `invalid_setting` | Unknown settings key or value |
| 400 | `invalid_addon` | Addon id is not in the catalog |
| 400 | `invalid_role` | Role is not `member` or `builder` |
| 400 | `invalid_player` | Invite body has neither `name` nor `uuid` |
| 400 | `is_owner` | Kick or role change targets the owner |
| 401 | `missing_token` | API token header absent or wrong |
| 401 | `missing_identity` | Discord id header absent or not a snowflake |
| 403 | `not_linked` | Discord id has no Minecraft UUID |
| 403 | `no_rank` | Create while resolved slots are 0 |
| 403 | `not_owner` | Mutation by someone other than the owner |
| 404 | `world_not_found` | Unknown id, or not owned by the caller |
| 404 | `player_not_found` | Name is not in `known_players` |
| 404 | `member_not_found` | Kick or role change for a player who is not a member |
| 409 | `name_taken` | This owner already has that name |
| 409 | `no_free_slot` | Every configured slot is `online` or `starting`, or `max-running` is reached |
| 409 | `already_member` | Invite of a current member |
| 409 | `addon_limit` | Selection is longer than `addons.max` (default 3) |
| 429 | `rate_limited` | Per-Discord limit |
| 503 | `ram_budget` | Host RAM budget refuses another world |
| 503 | `host_unavailable` | Docker or the host status call failed |
| 503 | `api_disabled` | Token or database host is not configured |
| 503 | `rank_unavailable` | LuckPerms rank could not be read |

Messages for the codes the game also shows in chat:

| code | message |
|---|---|
| `invalid_name` | Der Name muss 3 bis 24 Zeichen lang sein und darf nur Buchstaben, Zahlen, _ und - enthalten. |
| `no_rank` | Du hast keinen freien VIP-Weltplatz. |
| `name_taken` | Du hast bereits eine Welt mit diesem Namen. |
| `no_free_slot` | Alle Slots belegt. |
| `ram_budget` | Gerade kein RAM frei. |
| `host_unavailable` | VIP-Host nicht erreichbar, bitte spaeter erneut versuchen. |
| `world_not_found` | Deine VIP-Welt wurde nicht gefunden. |

## Worlds

### `GET /api/v1/vip/me`

```json
{
  "uuid": "1b2c3d4e-0000-4000-8000-000000000001",
  "discordId": "123456789012345678",
  "rank": "mvp",
  "admin": false,
  "slots": { "total": 3, "used": 1 },
  "playerLimit": 10,
  "worlds": [
    {
      "id": "7f1e0000-0000-4000-8000-000000000010",
      "name": "Insel",
      "type": "normal",
      "status": "running",
      "visibility": "private",
      "slot": "vipworld-2",
      "playerLimit": 10,
      "players": 2,
      "addons": ["keep-inventory"],
      "createdAt": 1793500000000,
      "lastStartedAt": 1793501000000
    }
  ]
}
```

`rank` is `admin`, `mvp`, `vip`, or `none`. `slots.used` counts non-deleted worlds. `players` is the number of `player_presence` rows with `online=true` for that slot name, or `0` when `slot` is null. Timestamps are epoch milliseconds. `lastStartedAt` is null before the first successful start.

`playerLimit` on `/me` is the cap that a newly created world would store. Each world object carries the cap stored for that world.

### `GET /api/v1/vip/runtime`

Capacity for the panel. RAM totals stay on the server.

```json
{
  "maxRunning": 2,
  "running": 1,
  "budgetOk": true,
  "playerLimits": { "vip": 5, "mvp": 10, "admin": 10 },
  "adminSlots": 3,
  "addonMax": 3,
  "slots": [
    { "slot": "vipworld-1", "status": "online", "worldId": "7f1e0000-0000-4000-8000-000000000010" },
    { "slot": "vipworld-2", "status": "offline", "worldId": null }
  ]
}
```

`running` counts slots whose status is `online` or `starting`. `budgetOk` is the host check for starting one more world.

### `POST /api/v1/vip/worlds`

```json
{ "name": "Bergdorf", "type": "normal" }
```

`type` defaults to `normal` when omitted. `201`:

```json
{
  "id": "c4d20000-0000-4000-8000-000000000020",
  "name": "Bergdorf",
  "type": "normal",
  "status": "stopped",
  "visibility": "private",
  "slot": null,
  "playerLimit": 10,
  "players": 0,
  "addons": [],
  "createdAt": 1793502000000,
  "lastStartedAt": null
}
```

### `GET /api/v1/vip/worlds/{id}`

Same object as one `worlds[]` entry. `{id}` is the world UUID.

### `PATCH /api/v1/vip/worlds/{id}`

Rename, visibility, or both.

```json
{ "name": "Hafen", "visibility": "public" }
```

`200` returns the world object. Visibility is `private` or `public`. A public world can be joined by any player up to `playerLimit`. Only the owner can change it.

### `POST /api/v1/vip/worlds/{id}/start`

No body. Idempotent when the world is already up: `200` and `"status": "running"`. A new start is `202`:

```json
{
  "id": "c4d20000-0000-4000-8000-000000000020",
  "name": "Bergdorf",
  "type": "normal",
  "status": "starting",
  "visibility": "private",
  "slot": "vipworld-1",
  "playerLimit": 10,
  "players": 0,
  "addons": [],
  "createdAt": 1793502000000,
  "lastStartedAt": null,
  "progress": {
    "phase": "starting",
    "server": "vipworld-1",
    "message": "VIP-Welt startet auf vipworld-1"
  }
}
```

`progress.phase` is `starting`, `ready`, or `failed`. `progress.server` is the Velocity server name (`vipworld-1`, `vipworld-2`, or `vipworld-3`). The in-game client connects the player when the phase becomes `ready`. The website only displays `progress`.

`409 no_free_slot` and `503 ram_budget` are error envelopes. The world stays `stopped`. There is no queue body.

### `POST /api/v1/vip/worlds/{id}/stop`

No body. `200` with `"status": "stopping"` and the slot that was stopped. Stopping an already stopped world returns `200` with `"status": "stopped"` and `"slot": null`.

### `GET /api/v1/vip/worlds/{id}/status`

The world object plus `progress`. `progress` is null while `status` is `stopped`. While `starting`, phase is `starting` and the message is `VIP-Welt startet auf vipworld-1`. While `running`, phase is `ready` and the message is `Bereit auf vipworld-1`. A start that was accepted and then failed (timeout, host dropped, world folder rejected) stores `status=error`; phase is `failed` and the message is `VIP-Host nicht erreichbar, bitte spaeter erneut versuchen.`, `Start dauert zu lange. Versuch es gleich nochmal.`, or `VIP-Welt konnte nicht gestartet werden.` Slot and RAM refusals are the `409` and `503` responses on start, not an `error` world status.

## Members

Owner only. Roles are `member` and `builder`. `builder` is the trust role (settings and build tools inside the world). Both roles can join. The owner is not a row in this list.

### `GET /api/v1/vip/worlds/{id}/members`

```json
{
  "worldId": "c4d20000-0000-4000-8000-000000000020",
  "members": [
    { "uuid": "aaaaaaaa-0000-4000-8000-0000000000aa", "name": "Steve", "role": "builder", "addedAt": 1793503000000 }
  ]
}
```

`name` is null when the UUID is not in `known_players`.

### `POST /api/v1/vip/worlds/{id}/members`

```json
{ "name": "Steve", "role": "member" }
```

`uuid` may be sent instead of `name`. `role` defaults to `member`. `201` returns the member object. Inviting the owner is `400 is_owner`.

### `PATCH /api/v1/vip/worlds/{id}/members/{uuid}`

```json
{ "role": "builder" }
```

`200` returns the member object. This is the trust action.

### `DELETE /api/v1/vip/worlds/{id}/members/{uuid}`

`204` with an empty body.

## Settings

Allowlist: `gamemode` (`survival`, `creative`, `adventure`), `difficulty` (`peaceful`, `easy`, `normal`, `hard`), `pvp` (boolean), `keepInventory`, `mobGriefing`, `doDaylightCycle` (booleans), `visibility` (`private`, `public`).

### `GET /api/v1/vip/worlds/{id}/settings`

Defaults filled in:

```json
{
  "worldId": "c4d20000-0000-4000-8000-000000000020",
  "gamemode": "survival",
  "difficulty": "normal",
  "pvp": false,
  "keepInventory": false,
  "mobGriefing": true,
  "doDaylightCycle": true,
  "visibility": "private"
}
```

### `PUT /api/v1/vip/worlds/{id}/settings`

Partial. Omitted keys stay as they are. `200` returns the full settings object. The world server re-reads these rows about once a second while the slot is running and applies them there. A stopped world applies them the next time it starts.

## Addons

V1 addons are `type: rule`. They are selected per world, stored in `vip_world_addons`, and applied by CraftCore on the world server. They are not downloadable jars. `type: plugin` is reserved and is not in the catalog. The same ids are toggled from the in-game GUI.

| id | Effect |
|---|---|
| `keep-inventory` | `keepInventory=true` |
| `peaceful` | difficulty `peaceful` |
| `creative` | gamemode `creative` |
| `day-lock` | `doDaylightCycle=false` |
| `pvp` | world PvP on |

Addon effects override the matching settings keys while the addon is selected. Removing the addon leaves the underlying setting.

### `GET /api/v1/vip/addons`

```json
{
  "max": 3,
  "addons": [
    { "id": "keep-inventory", "name": "Keep Inventory", "description": "Behalte Items beim Tod", "type": "rule" },
    { "id": "peaceful", "name": "Peaceful", "description": "Keine feindlichen Monster", "type": "rule" },
    { "id": "creative", "name": "Creative", "description": "Kreativmodus", "type": "rule" },
    { "id": "day-lock", "name": "Tageslicht aus", "description": "Zeit steht still", "type": "rule" },
    { "id": "pvp", "name": "PvP", "description": "Spieler koennen sich treffen", "type": "rule" }
  ]
}
```

### `GET /api/v1/vip/worlds/{id}/addons`

```json
{ "worldId": "c4d20000-0000-4000-8000-000000000020", "addons": ["keep-inventory"] }
```

### `PUT /api/v1/vip/worlds/{id}/addons`

Replaces the selection. Order is ignored. Duplicates are collapsed.

```json
{ "addons": ["keep-inventory", "peaceful"] }
```

`200` returns the stored id list. An empty array clears addons.

## Internal lobby routes

These already exist and stay as they are. The website does not call them. The lobby plugin calls them with `X-VIP-Token: $VIP_HOST_TOKEN`. A bad token is `401` with an empty body. An unknown path is `404` with an empty body.

### `POST /vip/start`

```json
{ "worldId": "7f1e0000-0000-4000-8000-000000000010" }
```

| HTTP | Body |
|---|---|
| 202 | `{ "status": "starting", "slot": "vipworld-1" }` |
| 200 | `{ "status": "running", "slot": "vipworld-1" }` when that world is already online |
| 409 | `{ "status": "error", "code": "no_free_slot" }` |
| 503 | `{ "code": "ram_budget" }` |
| 400 | `{ "status": "error", "code": "invalid_request" }` or `invalid_world_id` or `invalid_world_path` |
| 500 | `{ "status": "error", "code": "base_missing" }` |
| 503 | `{ "status": "error", "code": "service_unavailable" }` |

### `POST /vip/stop`

```json
{ "slot": "vipworld-1" }
```

`200` `{ "status": "stopping" }`. An unknown slot is `400` `{ "status": "error", "code": "invalid_slot" }`.

### `GET /vip/status`

```json
{
  "slots": [
    { "slot": "vipworld-1", "status": "online", "worldId": "7f1e0000-0000-4000-8000-000000000010" },
    { "slot": "vipworld-2", "status": "offline", "worldId": null }
  ],
  "maxRunning": 2,
  "budgetOk": true
}
```

`status` on a slot is `online`, `starting`, `sleeping`, or `offline`. `maxRunning` is the configured cap after the host clamps it to 3. `budgetOk` is whether one more world would fit the RAM budget.
