# VIP management API

Contract for the website rework. The website writes VIP commands into its D1 queue. mc-stack polls that queue with the ingest token and executes them. The website does not call the Minecraft host, and it does not send RCON or chat commands.

Internal lobby routes at the bottom stay as they are. Only the lobby plugin calls them.

## Queue

Same shape as shop orders (`mc-shop-orders`): header `x-ingest-token: $MC_INGEST_TOKEN`, JSON `{ "items": [ ... ] }` on the poll, `{ "acks": [ ... ] }` on the ack.

| Call | Path |
|---|---|
| Poll | `GET /api/mc-vip-commands/pending` |
| Ack | `POST /api/mc-vip-commands/ack` |

Base URL is `MC_WEB_API`, default `https://nwebnotfound.dev/api`. A missing ingest token means mc-stack does not poll.

### Pending item

```json
{
  "request_id": "vip-7f1e-start",
  "discord_id": "123456789012345678",
  "created_at": 1793500000,
  "action": "start",
  "world_id": "7f1e0000-0000-4000-8000-000000000010",
  "info": "optional note"
}
```

`created_at` is unix seconds. `request_id` matches `^[A-Za-z0-9_-]{1,64}$`. It is unique per `discord_id`: the same pair is one command, and a later poll of that pair returns the ack already decided. A different Discord user may use the same `request_id` string. mc-stack stores the decision under `(discord_id, request_id)`.

`discord_id` is a snowflake, `^[0-9]{1,20}$`. `action` is one of `create`, `rename`, `start`, `stop`, `settings`, `invite`, `kick`, `trust`, `addons`. Extra fields depend on the action and are listed below. Unknown fields are ignored. A UUID or rank inside the item is not trusted.

### Ack

```json
{
  "acks": [
    { "id": "vip-7f1e-start", "discord_id": "123456789012345678", "status": "denied", "reason": "not_owner" }
  ]
}
```

| status | When |
|---|---|
| `done` | The action was applied |
| `denied` | The execution-time checks failed. The world is unchanged |
| `expired` | `created_at` is 600 seconds or older and the command was still pending. It is not executed |
| `failed` | Checks passed and the host or the database then failed |

`reason` on `denied` is `not_linked`, `no_rank`, `not_owner`, `not_member`, `name_taken`, `no_free_slot`, `invalid_name`, `invalid_type`, `invalid_setting`, `invalid_addon`, `invalid_role`, `invalid_player`, `is_owner`, `player_not_found`, `member_not_found`, `world_not_found`, or `addon_limit`. `reason` on `failed` is `ram_budget` or `host_unavailable`. `expired` and `done` send an empty reason.

## Checks at execution

mc-stack checks again when it runs the item. Values copied into D1 at enqueue time are not enough.

1. **Discord link.** The Minecraft UUID comes from `mc_links` for that `discord_id`. No row is `denied` / `not_linked`. If the item also contains `mc_uuid`, it must equal that UUID or the ack is `denied` / `not_linked`.
2. **Rank.** The linked UUID must currently be `vip`, `mvp`, or `admin`. Anything else is `denied` / `no_rank`. Admins count as allowed. Slot and player caps are in the rank table below.
3. **Owner or member.** `create` needs a free slot for that rank. Every other action needs the linked UUID to own the world, except `start` of a world where they are a member (join-only: the member ack is `done` only when the world is already `running`; a member cannot start or stop it). Mutations (`rename`, `stop`, `settings`, `invite`, `kick`, `trust`, `addons`) require the owner. A failed check is `denied` / `not_owner` or `not_member`.

A public world does not make the caller an owner.

| Who | World slots | Players in one running world |
|---|---|---|
| `vip` | meta `vipworlds.slots`, normally 1 (fallback meta key `vipworld-slots`) | 5 |
| `mvp` (meta slots >= 3) | meta, normally 3 | 10 |
| `admin` | max(meta, `admin-slots` from `config/vipworlds.yml`, default 3) | 10 |

`players.vip`, `players.mvp`, and `players.admin` in that file are 5, 10, and 10. RAM stays in the `ram` block. A start the budget refuses is `failed` / `ram_budget`. Survival's reserved RAM is unchanged. The owner and admins may be in a full world. Everyone else is refused at login when the online count has reached `playerLimit`.

## Free text

`info`, `name`, and any other free-text field is sanitized before it is shown in game or stored as a display string:

- Remove each `§` and the formatting character after it.
- Remove CR and LF.
- Trim, then cap at 120 characters (`name` still has to match the name rule after that).

The sanitized string is an argument to the plugin API (the same methods the chest GUI calls). It is never concatenated into an RCON line, a chat command, or `dispatchCommand`.

## Action payloads

World names are 3–24 characters from `[A-Za-z0-9_-]`. Types are `normal`, `flat`, `void`. Roles are `member` and `builder` (`trust` sets `builder`). Visibility is `private` or `public`.

Settings keys: `gamemode` (`survival`, `creative`, `adventure`), `difficulty` (`peaceful`, `easy`, `normal`, `hard`), `pvp`, `keepInventory`, `mobGriefing`, `doDaylightCycle` (booleans), `visibility`. Omitted keys stay as stored. Defaults for a new world: survival, normal, pvp false, keepInventory false, mobGriefing true, doDaylightCycle true, visibility private. A running world re-reads settings about once a second. A stopped world applies them on the next start.

Addon ids, max 3, all `type: rule`: `keep-inventory`, `peaceful`, `creative`, `day-lock`, `pvp`. They override the matching setting while selected. `addons` replaces the whole set. `[]` clears it.

| action | Fields | Notes |
|---|---|---|
| `create` | `name`, `type` (default `normal`), optional `info` | Stores `playerLimit` from the rank at this moment |
| `rename` | `world_id`, `name` | Owner |
| `start` | `world_id` | Owner. Already running is `done`. No free slot is `denied` / `no_free_slot`. RAM refusal is `failed` / `ram_budget` |
| `stop` | `world_id` | Owner. Already stopped is `done` |
| `settings` | `world_id`, `settings` object | Owner. Unknown key or value is `denied` / `invalid_setting` |
| `invite` | `world_id`, `name` or `uuid`, optional `role` (default `member`) | Owner. Name is resolved in `known_players` |
| `kick` | `world_id`, `uuid` | Owner. The owner row cannot be kicked (`is_owner`) |
| `trust` | `world_id`, `uuid` | Owner. Sets role `builder` |
| `addons` | `world_id`, `addons` array of ids | Owner |

`start` does not return a queue position. The in-game client shows boss bar and action bar progress and connects the player when the slot is ready. The website only learns `done`, `denied`, or `failed`.

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
