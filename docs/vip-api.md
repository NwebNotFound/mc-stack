# VIP management API

This file is the contract. The website copies it. mc-stack polls the queue and is the only component that rolls world changes, joins, and `expired`.

The website writes commands into its D1 queue and serves `GET /api/mc-vip-commands/pending`. mc-stack calls that route with the ingest token, applies the command through the plugin API, then `POST /api/mc-vip-commands/ack`. The website does not call the Minecraft host, does not roll randomness, and does not send RCON or chat commands.

The lobby plugin keeps calling `POST /vip/start`, `POST /vip/stop`, and `GET /vip/status` with `X-VIP-Token`. Those bodies are frozen at the bottom of this file. The website does not call them.

Base URL is `MC_WEB_API`, default `https://nwebnotfound.dev/api`. Header on poll, ack, and world sync: `x-ingest-token: $MC_INGEST_TOKEN`. A missing token means mc-stack does not poll. `Content-Type` is `application/json; charset=utf-8`.

## Poll

`GET /api/mc-vip-commands/pending`

```json
{
  "items": [
    {
      "request_id": "vip-7f1e-start",
      "discord_id": "123456789012345678",
      "created_at": 1793500000000,
      "action": "start",
      "world_id": "7f1e0000-0000-4000-8000-000000000010"
    }
  ]
}
```

`created_at` is epoch milliseconds. Every timestamp in this contract is epoch milliseconds. A `created_at` below `1000000000000` is not a millisecond value: mc-stack acks `denied` / `invalid_timestamp` and does not execute it.

`request_id` matches `^[A-Za-z0-9_-]{1,64}$` and is unique per `discord_id`. The same pair is one command. A later poll of that pair is answered with the ack already stored and is not executed again. Another Discord user may reuse the same `request_id` string. mc-stack stores decisions under `(discord_id, request_id)`.

`discord_id` matches `^[0-9]{1,20}$`. `world_id`, when present, is a UUID (`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`, lowercase). It is not a slug and not a world name.

`action` is one of `create`, `rename`, `start`, `stop`, `settings`, `set_visibility`, `invite`, `uninvite`, `trust`, `addons`, `delete`. Unknown fields are ignored. A UUID, rank, or visibility copied into D1 at enqueue time is not trusted; mc-stack reads the link, the rank, and the world again at execution.

## Ack

`POST /api/mc-vip-commands/ack`

```json
{
  "acks": [
    {
      "request_id": "vip-7f1e-start",
      "discord_id": "123456789012345678",
      "status": "denied",
      "reason": "not_owner"
    }
  ]
}
```

The ack identifies the command by `request_id` plus `discord_id`. It does not use a D1 row UUID.

| status | Who sets it | When |
|---|---|---|
| `done` | mc-stack | The action was applied |
| `denied` | mc-stack | A check failed. The world is unchanged |
| `expired` | mc-stack only | On poll, `created_at` is at least 600000 ms before the poll time and the command has not been executed. mc-stack does not execute it |
| `failed` | mc-stack | The checks passed and the host, the database, or the audit insert then failed. The world is unchanged |

The website stores the status mc-stack sends. The website does not set `expired`. A command still inside the 10 minutes stays pending until mc-stack acks it.

`reason` on `denied`: `not_linked`, `no_rank`, `not_owner`, `not_trusted`, `already_member`, `name_taken`, `no_free_slot`, `invalid_name`, `invalid_type`, `invalid_setting`, `invalid_visibility`, `invalid_addon`, `invalid_role`, `invalid_player`, `invalid_timestamp`, `is_owner`, `player_not_found`, `member_not_found`, `world_not_found`, `addon_limit`.

`reason` on `failed`: `ram_budget`, `host_unavailable`, `audit_failed`.

`done` and `expired` send `"reason": ""`.

## Checks at execution

mc-stack checks again when it runs the item.

1. **Discord link.** The Minecraft UUID is the website's verified `mc_links` row (D1) for that `discord_id`. The website sends it in each item as `actor_uuid` (`mc_uuid` is accepted as the older name) together with `linked`, which the website computes when it serves the pending list: `true` only if that `mc_links` row is still verified, still has the Discord role and still has the same UUID as at enqueue. mc-stack uses `actor_uuid` only when `linked` is `true`; `linked: false` acks `denied` / `not_linked` even if a local row exists; without `linked` the website UUID is not trusted. MariaDB `discord_links` is only filled by `/cclink` and is not the source: if it has a row for that `discord_id` that differs from `actor_uuid`, the ack is `denied` / `not_linked`. An item without `actor_uuid` and without a `discord_links` row acks `denied` / `not_linked`. A malformed `actor_uuid` with valid `request_id` and `discord_id` acks `denied` / `invalid_player` and is not executed; an item without usable identifiers is skipped. A web `delete` carries the name the user typed as `confirm`; mc-stack compares it with the world's current name, so a rename in between acks `denied` / `invalid_name`.
2. **Rank.** `create`, and every action the caller takes as owner or admin, requires the caller's linked UUID to be `vip`, `mvp`, or `admin`. Anything else is `denied` / `no_rank`. A trusted member's `start` does not use the caller's rank. mc-stack reads the world owner's rank instead, and that owner must be `vip`, `mvp`, or `admin` or the start is `denied` / `no_rank`. Admins may use and manage worlds.
3. **Who may do what.**

| Action | Owner | Admin | Trusted invite | Plain invite |
|---|---|---|---|---|
| `create` | rank with a free slot | rank with a free slot | no | no |
| `start` | yes | yes | yes | `denied` / `not_trusted` |
| `stop`, `rename`, `settings`, `set_visibility`, `invite`, `uninvite`, `trust`, `addons`, `delete` | yes | yes | `denied` / `not_owner` | `denied` / `not_owner` |

A trusted invite may start the world and may not manage, invite, uninvite, or delete. Trust is a flag on that invite. It is not membership in the friends list. The owner turns it on with `trust` or with `invite` and `"role": "trusted"`. Whether trust grants `start` is the config flag `trusted-may-start` (default true). When the flag is false, a trusted invite is `denied` / `not_trusted` on `start` as well.

An admin who is not the owner may run every manage action, including `start`. mc-stack writes an audit row in the same transaction as the change. The row stores the admin's Minecraft name, the admin UUID, the target owner's name, and the target owner UUID. If the audit insert fails, the world is unchanged and the ack is `failed` / `audit_failed`.

`create` needs a free world slot for the resolved rank. A public-style join rule does not make the caller an owner.

| Who | World slots | Players in one running world |
|---|---|---|
| `vip` | meta `vipworlds.slots`, normally 1 (fallback meta key `vipworld-slots`) | 5 |
| `mvp` (meta slots >= 3) | meta, normally 3 | 10 |
| `admin` | max(meta, `admin-slots` from `config/vipworlds.yml`, default 3) | 10 |

`players.vip`, `players.mvp`, and `players.admin` are 5, 10, and 10. RAM stays in the `ram` block of `config/vipworlds.yml`. A start the budget refuses is `failed` / `ram_budget`. Survival's reserved RAM is unchanged. The owner and admins may join a full world. Everyone else is refused at login when the online count has reached `playerLimit`.

## Join rules

The world server checks permission on join. The website cannot grant a join.

| `visibility` | Who may join |
|---|---|
| `friends` (default) | Owner, admins, invited players, and players on the owner's friends list |
| `private` | Owner, admins, and invited players |

The friends list is the friends system. It is not the trusted flag and it is not a Discord relation. A friend who has no invite may join in `friends` mode and may not start or manage the world. A trusted invite is an invited player who may also start. Owner and admins may always join, in both modes.

There is no `public` value. `set_visibility` with any other string is `denied` / `invalid_visibility`.

When visibility changes to `private`, players who are online on that world and are not the owner, an admin, or an invited player are sent to the lobby. Friends who are not invited leave at that moment.

If the friends list cannot be read, `friends` mode fails closed for everyone except the owner, admins, and invited players.

## Free text

Before a free-text field (`info`, `name`, or any other display string) is stored or shown, mc-stack:

- removes every code point in Unicode general category `\p{Cc}` (this includes newlines and the other C0/C1 controls)
- removes every code point in `\p{Cf}` (bidi overrides, zero-width characters, and the other format characters)
- removes each `§` and the formatting character that follows it
- trims
- caps the result at 120 characters

`name` must then match `^[A-Za-z0-9_-]{3,24}$` or the ack is `denied` / `invalid_name`. The sanitized string is an argument to the plugin API, the same methods the chest GUI calls. It is never concatenated into an RCON line, a chat command, or `dispatchCommand`.

## Actions

World names are 3–24 characters from `[A-Za-z0-9_-]`. Types are `normal`, `flat`, `void`. Invite roles are `member` and `trusted`. `visibility` is `friends` or `private`.

Settings keys, partial update, omitted keys stay stored: `gamemode` (`survival`, `creative`, `adventure`), `difficulty` (`peaceful`, `easy`, `normal`, `hard`), `pvp`, `keepInventory`, `mobGriefing`, `doDaylightCycle` (booleans). Defaults on create: survival, normal, pvp false, keepInventory false, mobGriefing true, doDaylightCycle true, visibility `friends`. Visibility is not a settings key; only `set_visibility` changes it. A running world re-reads settings about once a second. A stopped world applies them on the next start.

Addon ids, max 3, all `type: rule`: `keep-inventory`, `peaceful`, `creative`, `day-lock`, `pvp`. They override the matching setting while selected. `addons` replaces the whole set. `[]` clears it.

| action | Fields |
|---|---|
| `create` | `name`, `type` (default `normal`), optional `info` |
| `rename` | `world_id`, `name` |
| `start` | `world_id` |
| `stop` | `world_id` |
| `settings` | `world_id`, `settings` object |
| `set_visibility` | `world_id`, `visibility` (`friends` or `private`) |
| `invite` | `world_id`, `name` or `uuid`, optional `role` (default `member`) |
| `uninvite` | `world_id`, `name` or `uuid` |
| `trust` | `world_id`, `name` or `uuid` |
| `addons` | `world_id`, `addons` array of ids |
| `delete` | `world_id` |

`create` stores `playerLimit` from the rank at that moment. `start` of a world that is already `running` is `done`. `stop` of a world that is already `stopped` is `done`. `invite` of someone who is already invited is `denied` / `already_member`. `uninvite` of a player who is not invited is `denied` / `member_not_found`. `uninvite` of the owner is `denied` / `is_owner`. When the world is `private` and the uninvited player is on it, mc-stack sends that player to the lobby. `trust` sets the invite's role to `trusted` and does not grant manage, invite, or delete. `delete` removes the world from `vip_worlds` after the ack `done`. Names are resolved in `known_players`.

`start` does not return a queue position. The in-game client shows a boss bar and the action bar and connects the player when the slot is ready. The website only learns `done`, `denied`, `expired`, or `failed`.

In game, the same rules are `/vip invite <name>`, `/vip uninvite <name>`, and the visibility toggle in the in-world VIP menu. Those commands call this API. They do not build a command string from the typed name beyond the argument the command parser already split.

## World sync

mc-stack pushes the current worlds to `POST /api/mc-vip-worlds` with the ingest token.

```json
{
  "synced_at": 1793502000000,
  "vip_worlds": [
    {
      "id": "7f1e0000-0000-4000-8000-000000000010",
      "owner_uuid": "1b2c3d4e-0000-4000-8000-000000000001",
      "owner_name": "Nils",
      "name": "Insel",
      "type": "normal",
      "status": "running",
      "visibility": "friends",
      "slot": "vipworld-2",
      "player_limit": 10,
      "players": 2,
      "invites": [
        {
          "uuid": "aaaaaaaa-0000-4000-8000-0000000000aa",
          "name": "Steve",
          "role": "trusted",
          "added_at": 1793503000000
        }
      ],
      "addons": ["keep-inventory"],
      "settings": {
        "gamemode": "survival",
        "difficulty": "normal",
        "pvp": true,
        "keepInventory": false,
        "mobGriefing": true,
        "doDaylightCycle": true
      },
      "created_at": 1793500000000,
      "last_started_at": 1793501000000
    }
  ],
  "vip_admin_log": [
    {
      "at": 1793504000000,
      "admin_uuid": "bbbbbbbb-0000-4000-8000-0000000000bb",
      "admin_name": "Nils",
      "owner_uuid": "1b2c3d4e-0000-4000-8000-000000000001",
      "owner_name": "Ada",
      "world_id": "7f1e0000-0000-4000-8000-000000000010",
      "action": "set_visibility",
      "detail": "private"
    }
  ]
}
```

`vip_worlds` is the complete list of live worlds, at most 500 per sync and at most 1,900,000 bytes as stored JSON (UTF-8; D1 allows 2,000,000 per value). Nothing is ever shortened: above 500 worlds mc-stack skips the sync and logs `VIP-Sync`. A list over the byte limit is rejected whole by the website (400), mc-stack logs `VIP-Sync: … HTTP 400`, and the website keeps its last list. `visibility` on each world is `friends` or `private`. `settings` carries the six plain world rules the panel edits; when the key is absent the website applies the same defaults as this example. `invites` is the invite list. `role` is `member` or `trusted`. `name` is null when the UUID is not in `known_players`. `last_started_at` is null before the first successful start. `slot` is null when the world is stopped. `players` is the number of `player_presence` rows with `online=true` for that slot, or 0 when `slot` is null.

For each of `vip_worlds` and `vip_admin_log`:

- the key is absent: the website leaves its stored copy unchanged
- the key is present and the array is empty: the website clears that copy
- the key is present and the array is non-empty: the website replaces that copy with the array

`synced_at` is epoch milliseconds. `status` on a world is `stopped`, `starting`, `running`, `stopping`, or `error`. Deleted worlds are omitted.

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
{ "slot": "vipworld-1", "worldId": "<uuid>" }
```

`200` `{ "status": "stopping" }`. An unknown slot is `400` `{ "status": "error", "code": "invalid_slot" }`. `worldId` is optional; when sent, the host stops the slot only if it still runs that world, else `409` `{ "status": "error", "code": "world_mismatch" }`. A malformed `worldId` is `400` `invalid_world_id`.

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
