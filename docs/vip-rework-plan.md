# VIP worlds and scoreboards rework

The website contract is [vip-api.md](vip-api.md). The website enqueues VIP commands in D1. mc-stack polls them with the ingest token and executes them in-process. This plan covers the game-side gaps and the design the follow-up implements. Internal `POST /vip/start`, `POST /vip/stop`, and `GET /vip/status` response bodies stay as documented there.

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
10. **Challenges look sparse.** `/challenges` on Survival is a 27-slot chest: a nether star with the season score, then paper or lime dye with the lines "Fortschritt: n / goal" and a CP line. The pool is seven types (`KILL_SHEEP`, `KILL_ZOMBIE`, `WALK_NETHER`, `WALK_NORMAL`, `MINE_IRON`, `FISH`, `TRADE`), three random dailies and three random weeklies. A boss bar appears only for the challenge that just changed, for five seconds, with the title `name - n / goal`. The sidebar never lists them. Completion is one chat line. There is no action bar, no per-type icon, and no progress bar.

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
| Survival | Server Survival, CP, Team, Online, then up to three active challenges |
| PvP | Server PvP, Online, Match (assignment status, or `-`) |
| VIP world | Welt name, Spieler `n/limit`, Slot, Sichtbarkeit |

The sidebar timer starts on lobby, survival, PvP, and VIP. `/sidebar an|aus` stays. Survival rows are Server, CP, Team, and Online, then the challenge lines from the challenges section. Kill and death counters stay off that board.

### Access, caps, RAM

`VipLimits` resolves slots and the player cap from meta `vipworlds.slots`, then `vipworld-slots`, the admin flag, and `config/vipworlds.yml` (`players.vip: 5`, `players.mvp: 10`, `players.admin: 10`, `admin-slots: 3`). Those keys sit at the top level so the host YAML reader ignores them. The join gate allows the owner, members, public worlds, and the existing bypass. A non-owner at the cap is kicked. Host `budget_ok` is unchanged.

### Website queue

mc-stack polls `GET /api/mc-vip-commands/pending` and acks `POST /api/mc-vip-commands/ack` with `x-ingest-token`, same item/ack shape as shop orders. Details, action payloads, and the frozen host bodies are [vip-api.md](vip-api.md).

On execute, mc-stack reads the UUID from `mc_links` for that `discord_id`, requires rank `vip`, `mvp`, or `admin`, and requires owner or member as the action demands. Any miss acks `denied` and does not change the world. `request_id` is unique per `discord_id`; a repeat of the same pair returns the ack already stored. An item still pending 600 seconds after `created_at` acks `expired` and is not executed. Free-text fields (`info`, `name`) lose `§` color codes and newlines, then go into the plugin methods the GUI uses. They are never placed on a command line (`dispatchCommand`, RCON, or chat commands).

### Challenges

Survival only. The period rules stay: Europe/Berlin, reset at 05:00, weekly key on Monday, three dailies and three weeklies per player, CP still 20–50 daily and 150–300 weekly, season points unchanged. New types are more rows in `config.yml`, not a second reward scale.

**GUI.** `/challenges` opens a 54-slot inventory. The top row is the season score, the reset clock, and a short hint. Dailies sit on the next two rows, weeklies on the last two, with a glass divider between them. Each task uses a material for its type (iron ore, wheat, fishing rod, map, crafting table, emerald, saddle, and so on). The lore is a fixed-width bar (`████░░░░░░`) plus `n / goal`, the reward, and `Taeglich` or `Woechentlich`. Finished tasks are lime and glowing. Clicking does not run a command.

**Sidebar.** On Survival the board appends up to three incomplete tasks after Online. Order is daily before weekly, then the highest progress ratio. Each line is a short title and `n/goal`. Completed tasks drop off the board. Lobby, PvP, and VIP keep the rows in the table above.

**Feedback.** Progress updates one boss bar (fraction, title `name n/goal`) and the action bar with the same text. The bar stays until the next change or about five seconds. On completion the bar switches to yellow, the action bar reads `Belohnung: 30 CP, 10 Season-Punkte`, a single chat line repeats that, and the player hears the level-up sound. Several completions in one tick become one summary line.

**Types.** Keep the seven existing ids. Add, with Survival-mode listeners and the same anti-fly rules the walk tasks already use:

| Type | Counts |
|---|---|
| `MINE_ORE` | Any ore block |
| `MINE_STONE` | Stone, deepslate, and their cobble variants |
| `FARM_HARVEST` | Fully grown wheat, carrots, potatoes, beetroots |
| `FARM_BREED` | Animals bred |
| `KILL_HOSTILE` | Hostile mobs |
| `KILL_PASSIVE` | Passive animals |
| `CRAFT` | Items crafted |
| `PLACE_BLOCK` | Blocks placed |
| `WALK_END` | Horizontal blocks in the End, on foot |
| `RIDE` | Horizontal blocks in a vehicle, not while flying |
| `BIOME` | Distinct biomes this period (one flag row per biome) |
| `PLAYER_TRADE` | Completed player-to-player trades |

Daily and weekly pools each contain at least one task from mining, farming, mobs, exploration, crafting, fishing, building, trading, and travel, so a player is not offered three copies of the same action. `ChallengeBars` (the bar text, and which three lines the sidebar shows) is pure and unit-tested.

## Follow-up

Two implementation PRs on top of this branch, and no further plan PR.

1. VIP worlds: schema `scripts/vip-worlds-rework.sql`, clickable GUIs, start feedback, in-world menu, addon catalog, `/lobby`, per-server sidebars (Survival without the challenge lines yet), rank and player caps, and the D1 poller/executor with the checks, expiry, and text sanitizer. Tests cover those rules and the frozen host JSON.
2. Challenges: the GUI, sidebar lines, boss bar and action bar, the new types, and the pool in `config.yml`.

`plugins-src/craftcore` `gradle clean build` stays green on both.

## Out of scope

The website UI (D1 and the worker stay in the website repo), a waiting queue for world starts, soft-delete, world download, and player-uploaded plugins.
