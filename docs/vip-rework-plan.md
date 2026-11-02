# VIP worlds and scoreboards rework

The website contract is [vip-api.md](vip-api.md). Paths, auth, and payloads in that file are the API Cloud builds against. This plan covers the game-side gaps and the design the follow-up implements. Internal `POST /vip/start`, `POST /vip/stop`, and `GET /vip/status` response bodies stay as documented there.

## Current gaps

1. **Lobby GUI only points at commands.** `UserMenu.openVip` fills the chest with lore such as `/vw create` and click actions `vw start <name>` or a suggested chat command. There is no create, manage, settings, or members screen that performs the action.
2. **Start feedback is one chat line.** `LobbyVip.waitForWorld` sends "Deine Welt startet noch..." and, on timeout, tells the player to run `/vw join` again. There is no boss bar, action bar, slot name, or failure reason, and a ready world connects only after the blocking poll returns.
3. **A VIP server has no menu.** `LobbyVip` starts only when `CRAFTCORE_SERVER=lobby`. On `vipworld-*`, CraftCore returns during enable after the join gate and idle stop. Players inside a world cannot invite, kick, trust, change settings, or see status from an item or `/vw`.
4. **Addons are not selectable.** `config/vipworlds.yml` has no catalog. `vip_world_addons` is not created. The host mounts the world directory and does not load a per-world mode.
5. **Leaving is per-server.** PvP sends players to `lobby` with a plugin message. There is no Velocity command. `/lobby`, `/l`, `/hub`, and `/leave` are not registered, so VIP, survival, and PvP have no shared way back.
6. **One sidebar for lobby and survival only.** Lines are Server, CP, Online, and Team. The timer never starts on PvP or `vipworld-*` because those profiles return early. Survival has no separate layout, so a rework has to keep kill and death counters off that board.
7. **Admins cannot open a world of their own.** `VipPerks.slots` reads LuckPerms meta `vipworlds.slots` and returns 0 when it is missing. `scripts/luckperms-ranks.txt` does not set that meta on `admin`, and the key it writes for VIP/MVP is `vipworld-slots`. Join bypass for `group.admin` exists. Create does not.
8. **Player caps are not enforced.** The schema has members and visibility, and the join gate allows the owner and members. It ignores `visibility=public` and has no online limit. VIP 5 and MVP 10 are not in config.
9. **RAM rules already live in config** and in `tools/pvp-host-service` (`budget_ok`). The host reader only understands `max-running`, `slots`, and `ram`. Extra top-level keys are safe. The `ram` mapping itself must stay so Survival's reserve is unchanged.

## Design

### Clickable GUIs

`VipGui` screens are inventories. A click runs a `VipActions` operation. The hub lists owned worlds and free slots from `VipMenuModel`. A world opens a manage screen with Start, Stop, Join, Settings, Members, Addons, and Rename. A free slot opens a type screen (`normal`, `flat`, `void`); the next chat line is the world name and is consumed as input. Settings cycle the allowlist in [vip-api.md](vip-api.md). Members: left click kicks, right click sets `builder`. Addons toggle catalog ids. `/vw` with no arguments opens the hub. The existing `/vw create|rename|list|start|stop|join` syntax still works and calls the same actions.

### Start feedback

`VipStartProgress` maps host results to a phase (`starting`, `ready`, `failed`), the slot name, a progress fraction, and the German message from the API doc. The lobby shows that text on a boss bar and the action bar about once a second while it polls `GET /vip/status`. `ready` connects the player to that Velocity server. `failed` clears the bar and sends the message. `no_free_slot` and `ram_budget` fail immediately. Timeout uses the configured `start-timeout-seconds`.

### In-world menu

On `vipworld-*`, the same `/vw` command and a nether star (when hotbar slot 8 is empty) open status, members, settings, and addons for the world in `CRAFTCORE_VIP_WORLD_ID`. Mutations require the owner. Members can open status. The world server applies settings and rule-addons to loaded worlds. `scripts/vip-db-user.sql` gains the writes `craft_vip` needs for members, settings, and addons. Presence recording starts on VIP slots so `players` in the API matches who is online.

### Addons

`config/vipworlds-addons.yml` is the allowlist (`keep-inventory`, `peaceful`, `creative`, `day-lock`, `pvp`), all `type: rule`, max 3. Selection is stored in `vip_world_addons`. CraftCore applies the effects. The host start body stays `{ "worldId" }` only. Jars are not downloaded or mounted.

### `/lobby`

New Velocity plugin `plugins-src/lobby-velocity` registers `lobby` with aliases `l`, `hub`, and `leave`. From any backend it connects the player to the Velocity server `lobby`. A player who is already there gets a short confirmation. The decision (connect, already there, unknown server, console) is a pure function with a unit test.

### Scoreboards

`SidebarLayout` picks rows from the server name. The gold `CRAFT` title, blank score numbers, and `mc.nwebnotfound.dev` footer stay.

| Server | Rows |
|---|---|
| Lobby | Server Lobby, Online, CP |
| Survival | Server Survival, CP, Team, Online |
| PvP | Server PvP, Online, Match (assignment status, or `-`) |
| VIP world | Welt name, Spieler `n/limit`, Slot, Sichtbarkeit |

The sidebar timer starts on lobby, survival, PvP, and VIP. `/sidebar an|aus` stays. Survival rows are the four above.

### Access, caps, RAM

`VipLimits` resolves slots and the player cap from meta `vipworlds.slots`, then `vipworld-slots`, the admin flag, and `config/vipworlds.yml` (`players.vip: 5`, `players.mvp: 10`, `players.admin: 10`, `admin-slots: 3`). Those keys sit at the top level so the host YAML reader ignores them. The join gate allows the owner, members, public worlds, and the existing bypass. A non-owner at the cap is kicked. Host `budget_ok` is unchanged.

### Website API

Served as the new `/api/v1/vip` routes on the host service. Auth, JSON, and error codes are [vip-api.md](vip-api.md). `VIP_API_TOKEN` is a different secret from `VIP_HOST_TOKEN`. Unset token or database host returns `503 api_disabled` and leaves lobby routes up.

## Follow-up

The implementation PR is based on this branch. It adds the schema script `scripts/vip-worlds-rework.sql`, the GUI and menu, start progress, the addon catalog, the Velocity lobby command, per-server sidebars, limit enforcement, and the management routes. Tests cover the pure rules, host JSON fixtures from the API doc, and the website error envelope. `plugins-src/craftcore` `gradle clean build` stays green.

## Out of scope

The website UI, a waiting queue, soft-delete, world download, and player-uploaded plugins.
