# Gacha: technical design for approval

Status: **draft, not approved or implemented**. This is a proposed contract, not a description of live behaviour. Nils's decisions below override older satchel behaviour and any conflicting mockup. Website/Worker changes belong in `NwebNotFound/NwebNotFound`; Creepy changes belong to the Discord maintainer. No real money, paid CP, subscriptions, or payment provider is involved.

### Changes 04.11.2026

Added on Nils's feedback to this PR: **§5.1** item value score (`Wert`), tier fit and ability gate; **§5.2** satchel expected value, balance bar and publish warnings with example numbers for the five Beutel; **§6.1** ability limits (uses/charges, cooldown, depletion, durability, charge storage in the PDC); plus the matching schema fields in §2 and the new test cases in §8. Documentation only — no code, migration, deploy or restart in this PR. All numbers are proposals for approval and are computed with the formulas in §5.1/§5.2, not measured production values.

## 1. Decisions, existing system and flow

- Only CP earned in game pays for pulls. 1 CP = 100 integer cents. One pull or 10 independent pulls; duplicates remain items, with no duplicate compensation. No pity, guarantee, minimum rarity for 10, or hidden odds adjustment. Order high to low: `SSR+`, `SSR`, `SR`, `S`, `R`, `UC`, `C`.
- Resources (coal through diamond), building (daily wood/stone/etc. theme), weekly themed banner (e.g. Ninja, SSR+ grapple), equipment (lore, balanced stats), and adventure (rare useful abilities). Pools contain both vanilla and custom items. Actual prices, contents and chances need approval; do not infer production values from the mockups.
- The plugin is the sole roller and CP authority. MariaDB is authoritative for definitions, publication, schedules, account balances, draw outcomes and outstanding rewards. D1 is an untrusted, eventually consistent view plus a transport queue; it is authoritative only for its own queued request until the plugin decides it, never for money, winners, or delivery. Browser reveals are presentation only.

```mermaid
flowchart LR
  GUI[In-game GUI] --> P[Survival CraftCore]
  P <--> M[(MariaDB: economy, versions, draws, rewards)]
  W[Website] -->|session + CSRF + Turnstile: request| API[Worker]
  API <--> D[(D1: queue + read models)]
  B[Creepy relay] -->|token: pull requests| API
  B -->|validated request, existing internal RCON path| P
  B -->|read result from restricted MariaDB view; token ack| API
  M -->|restricted views| B
  B -->|mc-ingest snapshots + availability| API
  D -->|private status| W
  P -->|online now / next join| I[Player inventory or pending pickup]
```

Baseline at mc-stack `e58cd17` / website `6c10bb5` (source, not verified deployment): `Satchels.java`, `SatchelStore.java`, `SatchelOdds.java` already charge through `EconomyStore.change` with `SecureRandom` in `core.tx` and insert `satchel_claims`. They implement **pity SR/SSR**, a single combined item-weight lottery, and lack tier `S`; `/satchel` displays pity. Those must change, not coexist as a bypass. `BankBuy.java` uses `bank_buy_orders` and a request-ID journal, transactionally commits charge and `pending_deliveries`; `Deliveries.java` and `/abholen` implement pickup, but inventory and DB cannot commit atomically. `ECONOMY-AUDIT.md` records this boundary. Existing `Satchels.deliver` marks a claim given before `addItem` and writes leftovers later: a crash can lose an item; do **not** claim exactly-once delivery today. SQL work currently in delivery on the main thread must move off it.

Creepy's `bot/relay.py` polls `mc-shop-orders/pending?types=shop,bank_buy` every 60 s with `x-ingest-token`, validates links, calls internal RCON, persists retries in SQLite and reads final bank results from MariaDB before ack. `bot/ingest.py` pushes server status every 60 s to `POST /api/mc-ingest`; website `src/worker.js` merges it into D1 `mc_snapshot`. `src/mc-trade.js` provides the analogous queue and Discord link checks; its Turnstile is currently optional, **not sufficient** for this design. `docs/vip-api.md` is a second queue/ack contract. None of these existing paths already supports gacha. Never expose RCON/DB or an ingest credential to the browser.

## 2. Data model and versioning

Names below are proposed migrations, not existing tables. UUIDs are canonical strings, times UTC epoch milliseconds except D1's existing session schema. Constraints are explicit and migrations additive; no dropping existing player data, claims or pity rows. Money and weights are integer; immutable version rows are never updated after publication. All server writes are InnoDB transactions. Validate FKs and indexes in actual migration tests.

### MariaDB: authoritative

| Table | Columns and constraints | Indexes / use |
|---|---|---|
| `gacha_items` | `item_id` UUID PK, `name_key`, `created_by_uuid`, `created_at`; stable identity | PK |
| `gacha_item_versions` | `(item_id, version)` PK; `material`, `name_components_json`, `lore_json`, `rarity`, `max_durability`, `start_damage`, `unbreakable`, `enchantments_json`, `attributes_json`, `custom_model_data_json`, `bound`, `stack_size`, `definition_hash`, `created_at`, `state` | FK item; immutable snapshots; validate allowlists and limits, not arbitrary NBT or commands |
| `gacha_item_abilities` | `(item_id, item_version, slot)` PK; `ability_id`, `ability_schema_version`, `params_json`, `uses_max` (0 = unlimited), `cooldown_seconds` (0 = none), `on_depletion` (`item_breaks`, `ability_lost`, `stays_normal`), `consumes_durability` | FK item version; v1 allows 0 or 1 ability, framework allows later slots with explicit combination checks; limits validated against the registry ranges in §6.1, never free-form |
| `gacha_satchels` | `satchel_id` UUID PK, `slug` UNIQUE, `enabled`, `created_at` | unique slug |
| `gacha_satchel_versions` | `(satchel_id, version)` PK; `display_name`, `kind` (`permanent`, `daily`, `weekly`), `cost_cents`, `definition_hash`, `state`, `published_at`, `published_by_uuid` | FK satchel; positive checked price, immutable once published |
| `gacha_tier_weights` | `(satchel_id, version, theme_id, rarity)` PK; `weight_ppm` | FK version; exactly 1,000,000 ppm across all 7 tiers per resolved theme; zero allowed only for absent tier |
| `gacha_pool` | `(satchel_id, version, theme_id, rarity, item_id, item_version)` PK; `item_weight`, `quantity` | FK tier and item version; `item_weight > 0`, quantity bounded and safe as an inventory stack; every positive tier has entries; item rarity matches tier |
| `gacha_schedule` | `schedule_id` UUID PK, `satchel_id`, `version`, `theme_id`, `kind`, `weekday` nullable, `starts_at`, `ends_at` nullable, `timezone`, `priority` | `(satchel_id, starts_at, ends_at)`; no overlaps for same satchel; intervals `[start,end)`; daily schedule resolves local calendar date and handles DST deterministically; gaps disable pulls |
| `gacha_requests` | `request_id` UUID PK (same ID on D1 and server), `player_uuid`, nullable `discord_id`, `source`, `satchel_id`, `count`, `expected_version`, `max_cost_cents`, `payload_hash`, `created_at`, `expires_at`, `status`, `reason`, `resolved_version`, `theme_id`, `charged_cents`, `committed_at` | PK; `(player_uuid,created_at)`; status `committed`, `rejected` or `expired` (request may initially be D1-only); unique ID bound to canonical payload |
| `gacha_draws` | `(request_id, ordinal)` PK, `rarity`, `item_id`, `item_version`, `quantity`, `tier_roll`, `tier_total`, `item_roll`, `item_total`, `pool_hash`, `delivery_id`, `created_at` | FK request/item; unique `delivery_id`; ordinal 0..count-1. Immutable audit with versioned pool, weights and outcomes; don't log RNG secrets |
| `gacha_deliveries` | `delivery_id` UUID PK, `request_id`, `ordinal`, `player_uuid`, immutable item snapshot/serialized data, `quantity`, `cap_cents`, `state`, `attempt_id`, `updated_at`, `last_error` | UNIQUE `(request_id,ordinal)`, `(player_uuid,state,updated_at)`; `pending`, `in_progress`, `delivered`, `needs_review` |
| `gacha_admin_changes` | `request_id` PK, `actor_discord`, `actor_uuid`, `operation`, `expected_version`, `payload_hash`, `result`, `created_at` | `(actor_uuid,created_at)`; logs proposal, validation, publish or rejection without credentials |
| `gacha_admin_staging` | `request_id` PK, `actor_discord`, `actor_uuid`, `payload_json`, `payload_hash`, `created_at`, `expires_at` | PK; bounded untrusted proposal envelope, never published state; bot may INSERT/SELECT only, no UPDATE/DELETE or economy grants |
| `gacha_outbox` | `event_id` increasing PK, `request_id`, `kind`, `payload_version`, `payload_json`, `created_at` | unique `(request_id,kind,payload_version)`; relay reads via restricted view/cursor; retains replayable results and separate delivery updates |

Production uses the existing `accounts(uuid,balance)` row and its locking rules; do not create a second balance. One reward row may represent multiple units only if the cost cap and recovery rules apply to the entire quantity. Item snapshots must include all actual values, not just a reference to mutable configuration. On publication, store a canonical full pool hash. On retries use it and the original result, not current banner data.

Definition state and instance state stay separate: the tables above store only declared limits and pool data. Remaining charges, live cooldowns and the item `Wert` are **not** authoritative engine columns — charges live on the item instance, cooldowns in the persisted per-player row (§6.1), and the value is deterministically derived from the immutable snapshot on every save, publish and preview (§5.1). No client, D1 or relay payload field for charges, value or durability is ever persisted; only the plugin can write them.

### D1: projection and queue only

| Table | Columns | Indexes / use |
|---|---|---|
| `mc_gacha_catalog` | `id` PK = `current`, `version`, `catalog_json`, `updated_at` | PK; bounded public published satchels, schedules, prices, all tier/item odds and item display metadata; no unpublished definitions |
| `mc_gacha_requests` | `id` PK, `discord_id`, `mc_uuid`, `satchel_id`, `count`, `expected_version`, `max_cost_cents`, `payload_hash`, `created_at`, `expires_at`, `status`, `reason`, `result_json`, `delivery_state`, `result_version`, `updated_at` | PK; `(status,created_at,id)` for bounded relay poll, `(discord_id,created_at,id)` for own history. `pending` → final plugin `committed`/`rejected`/`expired`; monotonic delivery version, idempotent ack. 10 results in one bounded JSON row |
| `mc_gacha_admin_queue` | `id` PK, `actor_discord`, `actor_uuid`, `operation`, `expected_version`, `payload_json`, `payload_hash`, `created_at`, `status`, `reason`, `result_version` | `(status,created_at,id)`, `(actor_discord,created_at,id)`; drafts/proposals, not authoritative published items |
| `mc_gacha_rate` | `(bucket_type, bucket_key, window_start)` PK, `count`, `updated_at` | PK; fixed windows for account and keyed IP hash, no raw IP; atomic conditional upsert/batch, bounded windows, no historical `COUNT(*)` scans |

Reuse `sessions`, `mc_links`, server portion of `mc_snapshot` and published ranks where appropriate. Admin authorization at the Worker uses an indexed, fresh trusted rank projection; plugin independently rechecks live LuckPerms authorization at execution. Add an indexed normalized UUID rank lookup if needed rather than the current `lower(replace(uuid,...))` scan. Versioned ack must never overwrite a newer or terminal state with an older state. For pulls, the relay needs only SELECT views: a strict console-only plugin command accepts validated IDs and bounded typed arguments. For admin proposals Creepy rechecks identity, inserts the bounded canonical envelope into `gacha_admin_staging` using a separate narrowly scoped INSERT/SELECT DB principal, and dispatches only the ID and hash. The plugin reads the staging row, checks identity, deadline, hash, current permission and full schema before any publish; the bot cannot update the row or publish definitions. Duplicate staging ID with different payload is rejected. Audit every privilege grant. Never concatenate raw free text into RCON; no new public host listener.

Existing `satchels`, `satchel_items`, `satchel_pity` and `satchel_claims` stay intact. Migration must preserve and deliver all old open claims and their cap; map legacy published satchels to explicit versioned tier probabilities, including absent S, only after review. Existing claims retain their exact item bytes; no reroll. Disable old pity and old draw entry points atomically with enabling the new engine; keep `/satchel` as an alias or documented safe migration. No fallback to the old pity engine if new tables are missing. Take a backup before any future migration/deploy; rollback must preserve any post-migration draws/claims and is not a destructive restore.

## 3. Drawing, atomicity and recovery

At execution of a website request, Creepy rechecks the exact Discord↔UUID pair against its active SQLite link and the Discord role immediately before dispatch, failing closed if either is unavailable. Its trusted, console-only typed dispatch carries request ID, Discord ID, UUID and validated pull fields; the plugin checks their syntax, binding and deadline and independently checks survival availability, active schedule, published version and exact confirmed max price. D1's link is only an admission hint; the authenticated relay is trusted for the live link assertion, so test revocation races and keep the assertion/dispatch window short. In-game requests use the authenticated player UUID directly and do not require a Discord link. Website gets the version and exact price before consent; if either changed, reject `STALE_CATALOG`/`PRICE_CHANGED` without charge. Resolve theme by the server's clock at execution; a changed theme/version requires renewed consent, never a silent substitution.

Within one MariaDB transaction: insert/lock `gacha_requests` by `request_id`; mismatching immutable payload => `ID_CONFLICT`, identical final => return prior result. Lock the published resolved version and the account row, check sufficient cents and `Math.multiplyExact`; for each of 1 or 10 independent outcomes use `SecureRandom.nextLong(bound)` (unbiased bounded sampling), first against 1,000,000 tier ppm, then against the sum of positive item weights in that tier. `P(item) = tier_ppm / 1,000,000 × item_weight / tier_item_weight_sum`. One item per outcome; store both sampled integers, bounds, immutable pool/version hash, item and quantity. No seeded client RNG, no reroll on retries, no pity state. Debit via `EconomyStore.change`, insert request, all draw/delivery rows and outbox in the **same** commit. An insufficient balance or invalid pool creates a durable rejected request without debit; exceptions roll everything back. With simultaneous in-game/web requests the existing account row lock serializes spending; DB unique keys, not the in-memory `busy` set, enforce idempotency. Timeout or lost ack retries the same ID; relay reads final journal/outbox before declaring anything failed.

Crash before commit: no charge or result. Crash after commit but before delivery/ack: original charge, results and pending deliveries survive; replay outbox, no second charge. Expiry is checked against DB time inside the plugin transaction immediately before debit; a request first executed after its deadline is rejected on replay even if the server later starts. A transaction that passed the deadline check may commit just after the deadline: it remains the one authoritative result. Never let D1 independently mark a request failed/expired while a plugin transaction may have committed; if the outcome is unknown, show `processing/reconciliation`, not `failed` or a refund. Network recovery reconciles by ID; never silently create a replacement ID.

**Inventory boundary / release gate:** MariaDB and Paper player-data saves are not one atomic transaction. A journal alone cannot guarantee exactly once across a crash after inventory mutation but before durable save/DB ack, or vice versa. Design a unique delivery receipt, async DB reservation, main-thread inventory operation, forced/durable player save and startup/join reconciliation of the receipt against player data, including partial/full inventory and concurrent joins. Receipt must survive transfers, stacking and consumption until reconciliation; do not assume this without a proven design. If proof is impossible for a crash window, park it as `needs_review`, do not blindly redeliver or mark delivered, alert an admin with the immutable snapshot, and require an explicit reconciliation procedure. The implementation PR must demonstrate crash injection at every boundary and document remaining ambiguity; otherwise automatic production delivery is blocked. Do not claim exactly-once semantics. Online delivery is attempted promptly; offline delivery on next Survival join, leftovers remain pending and `/abholen` remains available. Ensure main-thread-only Bukkit access and async SQL.

Preserve existing satchel cap on old rewards. New reward resale must not create a guaranteed CP loop: bound items have no bank/player trade payout; for sellable resource quantities, total possible bank payout per reward is at most its single pull cost (not cost per unit), including stack splits, roundings, all market GUI paths and cross-player sales. PDC tags carry identity, version, cap/binding and receipt; protect crafting, smithing, furnaces, stonecutter and Crafter as well as all other transformations. Evaluate recipes and player shops/AH/trade, containers, hoppers, drops, death and cross-server transfers. Preserve normal sale of non-gacha dirt, cobblestone and tools; `market.unsellable` stays empty. If the cap/identity cannot survive a path, block that path for tagged rewards until proven safe. `bound` means owner-bound, not a cosmetic label: define owner checks and exhaustive transfer tests before publication.

## 4. Website API, liveness and D1 budget

Proposed endpoints, all additions to the existing Worker routing:

- `GET /api/mc-gacha/catalog` → published catalog + version, schedule, UTC times and seven explicit tier percentages plus per-item probabilities; cache short/public only for catalog.
- `POST /api/mc-gacha/pulls` → `{request_id,satchel_id,count,expected_version,theme_id,max_cost_cents,turnstile}`; `count` exactly 1 or 10. Discord session and verified active linked role determine UUID server-side; never accept buyer UUID or price as authority. Exact approved HTTPS Origin, CSRF token bound to session, JSON-only, mandatory server-verified Turnstile fail-closed, request/body limits. Idempotent identical replay returns same request; same ID/different payload or identity is 409. New request returns 202 `pending`; 401/403 for auth, 409 stale catalog, 429 rate limit, 503 for stale/offline status or unavailable DB. Resolve request ID before consuming extra rate quota for legitimate retries.
- `GET /api/mc-gacha/pulls/{request_id}` → session must own the request; private `no-store`, 404 for absent/foreign ID, pending or plugin result forwarded by the authenticated relay and delivery state. Result is one bounded row; no public caching of player data.
- `GET /api/mc-gacha/pending?limit=50` and `POST /api/mc-gacha/ack` → only `x-ingest-token`, bounded batches, idempotent request ID and monotonically increasing result version. Ack includes exact committed results or terminal rejection, then later delivery state. Extend Creepy's SQLite retry/outbox and the restricted MariaDB view; never invent a terminal result from a timeout. Ack loss/repeated polls are safe.

Availability: extend the existing 60 s Servers-Push with a separate survival **CraftCore gacha-ready heartbeat**, generated only by the enabled, healthy plugin and seen by Creepy, not just a Minecraft ping. A Worker accepts new requests only if online and heartbeat ≤75 s old, catalog fresh and bridge healthy; plugin checks again during execution. Heartbeat is a best-effort admission hint, not proof that a server cannot stop one instant later. If server or bridge stops after 202, request remains unresolved; plugin's 180 s deadline ensures it cannot debit on a later restart after expiration. If the plugin committed before a lost response, eventually show that original result, even after the deadline. No offline queue that executes later.

UI: one shared in-flight request per user/tab with cross-tab coordination, disable repeat click, retry using same ID. Poll at 10, 20, 30, 45, 60, 90, 120, 150 s after 202 (maximum eight), pause when tab hidden and resume within the same window; after that show unresolved and explicit manual recheck, not an automatic infinite poll. Status access is rate-limited too. A committed 10-pull is sorted on the client **C, UC, R, S, SR, SSR, SSR+**, stable by original ordinal for ties; one card per tap, SSR+ cinematic then continue, finally all results overview. Sorting does not affect draws. Closing the tab doesn't cancel, refund, or reroll. Announce failures and delivery status accessibly.

D1 free tier currently allows **5,000,000 rows read and 100,000 rows written per day, account-wide**, with indexes adding writes; verify current limits and budget against the whole site, not just this feature. Source: [Cloudflare D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/). Worker already documents 03.11's history scan that exhausted the read limit (`src/worker.js`, `histPct`). Example conservative planning model, **not measured**: one accepted request, 1 or 10 results in one row, eight status polls, one relay fetch, one ack, two delivery state changes; indexed point reads, no retries. Estimated 8 initial row reads (session, link, snapshot, catalog, idempotency, account/IP/global fixed rate buckets), 16 status reads (session + owned request per poll), 1 relay row and 1 ack lookup = **26 reads/request**. Budget up to **20 written rows/request**, including three atomic rate-bucket updates, queue insert, terminal ack, two delivery updates and secondary index entries; actual engine/index costs must be measured, not assumed. Also reserve 1,440 reads/day for one empty relay query/minute, conservatively even if an empty indexed query reports zero; 1,440 incremental heartbeat writes/day and 20 catalog writes/day, even if existing server snapshot coalescing avoids some.

| Requests/day (1 or 10 items each) | Estimated added reads/day | Estimated added writes/day |
|---:|---:|---:|
| 100 | 1,440 + 100 × 26 = 4,040 | 1,460 + 100 × 20 = 3,460 |
| 1,000 stress case | 1,440 + 1,000 × 26 = 27,440 | 1,460 + 1,000 × 20 = 21,460 |

Excludes existing site workload, retries, catalog page views, admin edits, migrations, cleanup and unusual multi-tab clients; therefore not a hard upper bound. D1 bills rows scanned, including index effects, not returned rows or HTTP requests. Require `EXPLAIN QUERY PLAN`, D1 `meta.rows_read`/`meta.rows_written` for each statement and account dashboard metrics under concurrent requests with representative history. Cap normal gacha contribution at 50,000 reads and 10,000 writes/day, reserve headroom for existing traffic; stress table is a capacity thought experiment, not permission to exceed that cap. Alert at 50%/80% of the feature budget and disable **new** requests on threshold or D1 errors, preserving status, reconciliation and acks; never enable a paid plan. Counter enforcement must be concurrency-safe across Worker isolates (D1 atomic conditional batch or a verified free-tier shared limiter), not in-memory only. Initial limits: 2 new requests/min and 10/hour per Discord account, 4/min and 20/hour per keyed IP hash; stricter global cap if measured load warrants. Fixed windows and polling limits do not scan draw history; expiry/retention only via approved, indexed, bounded maintenance, never delete records without Nils's approval.

## 5. Admin editors and release workflow

Admin is not inferred from UI. Worker requires the same session, verified link/role, fresh rank `admin`, strict Origin/CSRF and mandatory Turnstile for mutations. A separate admin queue routes a versioned proposal through Creepy; server rechecks the actor's active Discord link and LuckPerms admin permission at execution, failing closed if either is unavailable. Only the plugin validates and publishes. Restrict relay secrets/DB views and log actor, immutable request ID, previous/new version, validation failures and approval result. A stale expected version returns conflict; no last-write-wins overwrite. Enforce payload size/schema and use a fixed registry of ability IDs, never scripts, arbitrary commands or executable text.

Item editor: allowed base material; styled name and lore using bounded sanitized structured components, no click/hover commands or unsafe HTML; rarity; maximum/starting durability and unbreakable; allowlisted compatible enchantments and levels; bounded attribute operation/slot/amount and aggregate/equipped-combination caps; ability ID + typed parameters; Custom Model Data as supported by the deployed Paper version; binding, stack size and live tooltip. Anti-OP validation is server-side on save **and** publish; an unbreakable item must still obey all caps. Numeric per-material/per-attribute maxima and prohibited combinations are explicit, reviewed versioned server policy, not adjustable by ordinary editor form or client payload. Changes to those rules need a separately reviewed PR and tests. No unsupported attributes, unsafe enchants or unregistered abilities; show precise validation errors. Preview is not authoritative. Every item additionally shows its computed `Wert` and tier verdict with the ability's charges, cooldown, depletion and durability (§5.1, §6.1); those four fields are not optional for any item that carries an ability.

#### 5.1 Item value score (`Wert`), tier fit and ability gate

The editor must answer two questions before anything is published: does an item match its rarity, and is a satchel worth its price? Both use one server-computed number, the `Wert` in value points (WP). It is a balancing aid only: not a price, not a bank value, not sellable and never convertible to CP in any code path, never credited, never read by a pull, refund, trade or market. `1 WP` is roughly the utility of 1 CP of bank value on tier `C`, which serves only as a mental anchor for reading the number. No editor field can set `W` directly.

`W = T(rarity) × (B + E + A)`, integer, computed server-side from the immutable version snapshot and deterministic; editor preview, save validation, publish validation and the simulation all call the same function, and only the server result counts (a client-sent value is ignored, like every other preview value in §5).

| Term | Definition | Anchor numbers |
|---|---|---|
| `B` base value | Reviewed market table (`config.yml` `market.min-price`/`max-price`) × quantity plus the plain vanilla value of the base item. For equipment `B` is capped at 480 WP (the price of three diamonds), so an expensive material cannot carry an item alone and the value must come from stats or the ability; resource bundles use the market price uncapped, because there the material is the reward. | iron ingot ≈5 CP, diamond ≈160, netherite ingot ≈3200; iron pickaxe `B` = 15, diamond pickaxe 480, netherite boots capped at 480 instead of ≈3200 |
| `E` enchantments/stats | Reviewed per-enchantment weight × level: Efficiency 8, Fortune 20, Mending 25, Silk Touch 30, Protection 12, Sharpness 12, Unbreaking 10, Looting 18. Levels above the vanilla maximum count ×0.5 and only where the review allows it. Allowlisted attributes get fixed weights (+1 attack damage 4, +1 armour 3, +10% speed 5). Aggregate and combination caps from §5 bound `E`. | Efficiency II = 16, Protection IV = 48, Silk Touch = 30 |
| `A` abilities | `A = (P / 4) × K × L` per ability slot. `P` reviewed power rating 1..100 from the ability registry (never an editor field); `K` cooldown factor: ≤5 s 1.00, ≤30 s 0.85, ≤2 min 0.70, ≤10 min 0.55, longer 0.45; `L` lifetime factor: unlimited 1.00, limited `min(0.85, 0.25 + 0.12 × uses)` (3 uses 0.61, 5 uses 0.85). | Erzspürer 4 uses/60 s = 5.8; Enterhaken unlimited/20 s = 10.6; Heimkehr 3 uses/10 min = 6.7; Flucht aus den Minen 3 uses = 10.7 |
| `T` rarity factor | `C` 1.00, `UC` 1.15, `R` 1.35, `S` 1.65, `SR` 2.10, `SSR` 2.80, `SSR+` 3.60. Prices rarity and prestige, not raw power: `T` makes tiers comparable inside one pool and feeds §5.2, so a high tier never needs inflated stats to feel valuable. | SSR `T` 2.80 |

Tier bands (`W`, checked against the item's own tier) and the minimum tier per ability:

| Tier | `W` band | Ability gate (minimum tier) |
|---|---|---|
| `C` | 10–160 | `double_jump`, `magnet`, `miners_light` ≥ `UC` |
| `UC` | 25–320 | `grapple`, `lumberjack`, `ore_sense` ≥ `R` |
| `R` | 60–600 | `mine_escape`, `homeward` ≥ `SR` |
| `S` | 150–1000 | |
| `SR` | 300–1700 | |
| `SSR` | 600–3000 | |
| `SSR+` | 1200–6000 | |

Bands overlap on purpose: a strong `C` gear item can legitimately be worth more than weak `SR` resource bundles, so the band is a guardrail against an item that is out of its league, not a strict partition. Over-band = `zu stark` and blocks publication; under-band = `zu schwach` and warns; an ability below its gate blocks publication even when `W` is in band. The bands are versioned review policy like the anti-OP limits, not editable in the form.

Worked examples (server formula, integer arithmetic rounded for display):

| Item | `B` | `E` | `A` | `W` | Band | Verdict |
|---|---:|---:|---:|---:|---|---|
| `C` iron pickaxe, Efficiency II, Erzspürer 4 uses | 15 | 16 | 5.8 | 37 | C 10–160 | `passt` |
| `SR` diamond pickaxe, Silk Touch, Enterhaken unlimited | 480 | 30 | 10.6 | 1093 | SR 300–1700 | `passt` |
| `SSR` netherite boots, Protection IV, Heimkehr 3 uses | 480 | 48 | 6.7 | 1497 | SSR 600–3000 | `passt` |
| `C` wooden pickaxe, Erzspürer 4 uses | 2 | 0 | 5.8 | 8 | C 10–160 | `zu schwach` + gate `Erzspürer ≥ R` → blocked |

Editor display: a value badge on each item card (`Wert 37 WP`), a tier-fit chip (`passt`, `zu stark`, `zu schwach`), a breakdown popover with `B`, `E`, `A`, `T` and the ability term including its `P`, `K`, `L` and chosen `uses`/cooldown, the delta against the previous version, and the same number in the live tooltip. Warning colours alone never block: blocking happens server-side on save and publish with a stable error code (`ITEM_OVER_TIER`, `ABILITY_TIER_GATE`), and preview is not authoritative.

Satchel editor: choose permanent/daily/weekly, name, integer CP cost; per-theme seven tier probabilities in integer ppm totalling exactly 100%, positive integer per-item weights, quantity and versioned item reference. Reject missing nonzero-tier items, overflow, malformed dates, overlaps, mismatched rarity or invalid items. Preview exact odds and per-item odds. Simulate 100/1,000/10,000 independent pulls locally with clearly labelled non-production randomness and no CP/item effects; show distribution and expectation, never imply a guarantee. Only the plugin publishing the whole immutable version changes live pools. Scheduled daily rotations and weekly starts/ends show timezone explicitly; no auto-default to an unspecified zone. The editor also shows the expected value, balance bar and publish warnings of §5.2 for the version being edited.

#### 5.2 Satchel expected value, balance bar and publish warnings

Expected value per pull is `EV = Σ P(item) × W(item)` over every pool entry, using the same integer probabilities as §3 (`tier_ppm / 1,000,000 × item_weight / tier_item_weight_sum`) computed from the immutable version being edited. Ten pulls are `10 × EV` because pulls are independent and there is no pity (§1). Because gacha items are not sellable (§3), `EV` is not a CP refund and never becomes a payout: it measures what a player receives, not what can be cashed out, and no configuration may create a CP loop. The editor also shows median and P90 from the local 10,000-pull simulation (§5) and the SSR/SSR+ frequency as "1 in N pulls".

The balance bar compares `EV / cost` for a single pull:

| Zone | `EV / cost` | Effect |
|---|---|---|
| red `zu stark` | ≥ 1.00 | hard block `EV_TOO_HIGH`: a pull may never be a guaranteed gain |
| yellow `grenzwertig` | 0.85–0.99 | warning; publication needs a written justification in the proposal |
| green `passt` | 0.55–0.84 | normal |
| yellow `dünn` | 0.40–0.54 | warning; publication needs a written justification |
| red `zu schwach` | < 0.40 | warning + explicit Nils approval; never silent |

Further publish checks (all evaluated server-side on publish, warnings cannot be dismissed in the UI):

| Check | Threshold | Effect |
|---|---|---|
| Item over its tier | `W` above the §5.1 band | block `ITEM_OVER_TIER` |
| Ability tier gate | ability below its minimum tier | block `ABILITY_TIER_GATE` |
| SSR+ frequency | > 0.50% per pull (1 in 200) | block `SSR_PLUS_TOO_COMMON`; recommended 0.15–0.30% for permanent/daily Beutel |
| SSR frequency | > 3.00% per pull | warning |
| Value concentration | one item > 25% of `EV` / > 40% | warning / block `VALUE_CONCENTRATION` (a "Beutel" that is really one item) |
| Free or negative price | `cost_cents` ≤ 0 | block `INVALID_PRICE`: nothing is free, no starter or bonus pulls |
| Empty positive tier, ppm ≠ 1,000,000, weight ≤ 0 | — | already rejected by the §5 satchel editor |

Example numbers for the five planned Beutel — **proposal, not approved prices or odds**; tier columns are percent per pull, `C`/`UC`/`R`/`S`/`SR`/`SSR`/`SSR+`:

| Beutel | Cost CP | Tier split % | `EV`/pull CP | `EV`/cost | SSR+ | Verdict |
|---|---:|---|---:|---:|---|---|
| Rohstoffe | 150 | 42.0 / 30.0 / 19.0 / 7.0 / 1.7 / 0.3 / 0 | 115.6 | 0.77 | – | green `passt` |
| Baumeister (daily) | 200 | 39.0 / 30.0 / 20.0 / 8.5 / 2.0 / 0.35 / 0.15 | 167.3 | 0.84 | 1 in 667 | green, upper edge |
| Wochenbanner (Ninja) | 350 | 35.0 / 28.0 / 20.0 / 10.0 / 4.5 / 2.0 / 0.5 | 245.8 | 0.70 | 1 in 200 | green `passt` |
| Ausrüstung | 450 | 40.0 / 28.0 / 18.0 / 9.0 / 3.5 / 1.2 / 0.3 | 360.8 | 0.80 | 1 in 333 | green `passt` |
| Abenteuer | 500 | 42.0 / 30.0 / 16.0 / 8.0 / 2.8 / 1.0 / 0.2 | 398.8 | 0.80 | 1 in 500 | green `passt` |

Equivalent 10-pull expectations: 1156 / 1673 / 2458 / 3608 / 3988 CP before rounding, and the per-tier average `W` used above (Rohstoffe 40/90/180/340/620/1100, Ausrüstung 120/250/450/800/1400/2400) is what the pool editor must actually reproduce. Every Beutel keeps a positive sink of at least 16% of spend, and the weekly banner is the only one at the 0.5% SSR+ ceiling; raising it, or cutting any cost without cutting the pool, moves the bar out of green and the publish is flagged.

Display: badge per item, tier chip, `EV` per pull and per 10, balance bar with the five zones and the hard blocks named inline (`EV_TOO_HIGH`, `SSR_PLUS_TOO_COMMON`, `VALUE_CONCENTRATION`), the SSR+/SSR "1 in N" lines, and the concentration table of the top five EV contributors. All of it is a balancing aid; the editor never sells, never converts to CP and never changes the published version. Only the plugin publishing the whole immutable version changes live pools; preview is not authoritative.

`Im Spiel testen (an mich)` creates a separately audited, admin-only, rate-limited **test-mode** request. The plugin rechecks the admin, applies the same validation, and offers an ephemeral, visibly tagged test item only in an isolated non-economic test context: no CP, no real reward/delivery, trading, storage, dropping, market, other-player transfer, or permanent world changes. No test item may persist into production inventory. If that isolation and safe cleanup cannot be proven, keep the button disabled and provide tooltip preview only; do not silently gift items. Publication is a separate explicit action. Ability cooldown/test effects cannot alter real claims or resource economy.

## 6. Ability framework and first wave

Registry maps stable `ability_id` + schema version to server-owned handlers with typed validated params, safe defaults, max/min, events, permission/world/claim checks and cleanup. PDC carries item ID, immutable item version, owner/binding and per-instance ID where necessary; lore/name is never authority. Only server creates and verifies stamps; ignore unknown or invalid versions. Persist cooldown deadlines per `(player_uuid, ability_id, instance_id)` where restarting would otherwise bypass them; synchronize online instances across backends. Event listeners run on Paper's main thread; DB, heavy queries and precomputation off-thread with bounded work and cancellation. Hooks cover interaction, sneak/move/damage, break/pickup/equipment, quit/death/world change and plugin shutdown; respect WorldGuard, CraftCore claims, existing teleport rules, PvP and protected inventories. Rate-limit particles/sounds and don't load arbitrary chunks. Failure must be safe, not silently bypass protection. Registering a new ability requires ID/schema, handler, declared event subscriptions, validation/permissions/cooldown, resource budget and unit + integration tests; editor receives a read-only capability/schema catalog, never code. Ability stacking is denied until reviewed.

#### 6.1 Ability limits: uses, cooldown, depletion and charge storage

Every ability on an item carries limits, not only parameters: `uses_max` (`0` = unlimited), `cooldown_seconds` (`0` = none), `on_depletion` (`item_breaks`, `ability_lost` = the item survives as a normal item without the ability, `stays_normal`) and `consumes_durability` (whether normal item damage also applies). Both limits may combine, e.g. Erzspürer with 4 uses and a 60 s cooldown can be used at most once per minute until the charges are gone. An ability with neither a cooldown nor a charge limit is a deliberate exception that the editor flags for review, not a default.

Charges are item state, never a server or client counter:

- Stored in the item PDC as `gacha:ability_id`, `gacha:instance_id`, `gacha:uses_max`, `gacha:uses_left`, `gacha:charges_version`; the persisted cooldown row `(player_uuid, ability_id, instance_id)` from §6 stays separate, so neither a restart nor a relog refunds a cooldown or a charge.
- Written only by the plugin. The client, website, D1, the request payload, the relay and the admin proposal never carry or decide a charge count; the plugin stamps `uses_max`/`uses_left` from the immutable version at delivery and again on reconciliation from the same snapshot. A website or bot payload field that claims charges is ignored exactly like any other client-supplied authority field (§3, §4).
- Any item with a per-instance counter is forced to `stack_size = 1`; identical-looking stacks must never share or split a counter. The satchel editor may not give a charged item a stack size > 1.
- `uses_left` is debited exactly once per successful, actually applied effect, in the same main-thread operation that writes the cooldown. If a protection, PvP, claim, permission or world check cancels the effect, or the effect throws, the charge is restored within that tick: no partial consumption, no negative count, and repeated triggers at 0 are a no-op (depletion is idempotent).
- Refill is impossible in v1: anvil repair and rename, grindstone, Mending, enchanting, crafting, smithing, inventory sorting and repair-cost tricks must all leave `uses_left` untouched, and those paths are covered by the transformation checks in §3. A later recharge feature needs its own reviewed rule (explicit cost, cap, never free) and its own PR.
- Lore is rendered server-side from the PDC, not frozen in the snapshot text, so a stale string can never show more charges than the item holds: `Fähigkeit: Erzspürer`, `Ladungen: 3/5`. The immutable version stores the template with a placeholder plus `uses_max`. An optional live line (`Bereit in 42 s`) appears only in the held-item tooltip, rate-limited.
- Charges ride on the item, so duplicating an item duplicates its charges: the tag and transfer protection of §3 is a precondition for publishing any charge-limited item. Charges are not in the database, a lost item loses its charges, and there is no restore path.
- When the last charge is spent: `item_breaks` destroys the item and announces it once, `ability_lost` removes the ability stamp and lore line and leaves the plain item (default for tools), `stays_normal` leaves the item without any stamp. The chosen variant is part of the immutable version and is shown in the editor and the reveal before the pull is confirmed.

Defaults per ability (proposal, review required; the editor may choose `uses` inside the registry range, widening the range or changing `on_depletion` needs a new ability schema version in its own PR):

| Ability | `uses` | Cooldown | `on_depletion` | Durability | Min tier | Rationale |
|---|---|---|---:|---|---|---|
| `grapple` Enterhaken | unlimited | 20 s | – | yes | `R` | movement tool, the cooldown is the limiter |
| `mine_escape` Flucht aus den Minen | 3 | 10 min | `ability_lost` | no | `SR` | get-out card; charges are the real cap, the boots stay usable |
| `miners_light` Grubenlicht | unlimited | none | – | no | `UC` | no competitive advantage; the editor flags "no limit" for explicit approval |
| `double_jump` Doppelsprung | unlimited | 10 s | – | yes | `UC` | mobility QoL, durability caps the grind |
| `lumberjack` Holzfäller | unlimited | 2 s per tree | – | yes | `R` | QoL tool, the axe's own durability is the natural cost |
| `magnet` Magnet | unlimited | 5 s | – | yes | `UC` | small bounded radius, low power |
| `ore_sense` Erzspürer | 4 (range 3–5) | 60 s | `ability_lost` | no | `R` | strong information ability, therefore charge-limited; picked item stays a normal pickaxe |
| `homeward` Heimkehr | 3 | 10 min | `ability_lost` | no | `SR` | recall must never be unlimited |

Rule for the wave: strong utility (information, escape, recall — `ore_sense`, `mine_escape`, `homeward`) is charge-limited; convenience and mobility use cooldown plus durability; only defensive or cosmetic light (`miners_light`) may be unlimited without cooldown, and even that needs explicit approval. These limits feed the value formula in §5.1, so editing `uses` from 5 to 3 visibly lowers `W` and the satchel `EV`, and changing a limit after publication requires a new item version — no in-place edit of a live item.

| Wave 1 | Trigger / mechanics | Initial bounds and safety gates (proposals) |
|---|---|---|
| `grapple` Enterhaken | Right-click, ray trace block, pull **player** toward point | ≤16 blocks; 20 s cooldown; no entity hooks, protected destinations, cross-world/chunk loads or fall-damage bypass; velocity capped; cancel on quit/world change |
| `mine_escape` Flucht aus den Minen | Sneak and stand still 5 s; teleport to safe surface | 10 min cooldown; cancel on any movement, damage, release, logout; Survival overworld only; find safe loaded surface with solid floor, two air blocks and WorldGuard/claim permission; no safe spot = no teleport/no cooldown; never bypass combat/teleport restrictions |
| `miners_light` Grubenlicht | Wearing helmet illuminates nearby area | Bounded radius/updates; use a verified Paper-compatible non-destructive, per-player light solution; never permanently place/delete blocks or modify protected chunks. If compatibility cannot be proven, keep disabled |
| `double_jump` Doppelsprung | One extra jump before landing while wearing item | 10 s cooldown; cap velocity; no creative-flight override, elytra/PvP/claim bypass; restore prior flight state on removal/quit; test anticheat compatibility |
| `lumberjack` Holzfäller | Break a log with item; process connected logs | ≤16 natural connected logs, one bounded batch, no leaves/player-placed blocks; normal break events, durability and drop rules for each; check permission on each block, stop on cancel/unloaded chunk; default disabled until provenance is reliable |
| `magnet` Magnet | Equipped/held item attracts nearby dropped items | ≤3-block radius, bounded entities/tick, only drops player is already allowed to pick up; obey owner delays, claims and inventories; no through-wall/other-player theft |
| `ore_sense` Erzspürer | Right-click to sense nearby ore | ≤8 blocks, 60 s cooldown, only loaded chunks and authorized area; coarse direction/count, no exact wallhack coordinates or protected-area leakage; bounded scan, configurable ore allowlist |
| `homeward` Heimkehr | Right-click, then normal warm-up | Reuse existing approved home teleport target, delay, cancellation, cost and safety/claim checks; 10 min cooldown; no free bypass of existing costs, combat or dimensions |

These numerical caps are conservative implementation proposals, not approved balance. The supplied `McItemEditor.html` references `{{abilities}}` and says 25, but its static markup does **not** contain the 25 names. Framework supports future entries without reserving/inventing the other 17; request the actual list before designing their individual mechanics. Only the eight registered and tested abilities may be selectable.

## 7. In-game UX and announcement

`/gacha` opens the Survival GUI (and `/satchel` aliases the same engine). List active Beutel, theme/end time, cost, exact rarity/item chances, one/ten buttons and explicit no-pity text. Each item preview shows its ability limits from §6.1 (charges as `Ladungen: 3/5`, cooldown, and what happens when the last charge is spent) and never promises more uses than `uses_max`; the `Wert` from §5.1 is an admin-only number and never appears in player UI. Confirm total CP and pool version, then show final result or clear insufficient-balance/stale/unavailable message. Do not display a guaranteed interval; expected SSR+ frequency is only a mathematical average. Inventory full/offline shows pending pickup, not failure; never expose another player's requests. Reveal sound/title uses actual result. SSR+ only: broadcast a short server message after committed result, with rate limit/coalescing (e.g. one per player per minute, one global per 10 s) and no private data; never announce a test or a rejected/rolled-back request.

## 8. Small PRs, tests and acceptance gates

All implementation requires this design's approval first; each PR reviewed and CI green. No PR below implies deployment. Bot work is handed to the Discord owner with a `bot/changelog.json` entry; Worker/site work to Cloudflare owner. Cross-repo contracts and feature flags must land in compatible order. Do not expose a feature in a partially deployed state.

1. **CraftCore core/migration:** additive schema, seven tiers/no pity, immutable versions, SecureRandom two-stage selection, idempotent transaction, legacy-claim preservation, safe disabled-by-default `/gacha` alias. Tests: odds boundaries and zero tiers, exact ppm, deterministic injected RNG, independent 10 pulls, stale/expired/insufficient balance, concurrent double IDs and account races, DB rollback, old claims and S. Acceptance: one charge and exactly one DB result/claim per ordinal; no player-data deletion or old-engine bypass.
2. **Delivery hardening:** receipts, crash reconciliation, async SQL, full inventory and protected-item/market enforcement. Fault-injection tests before/after every DB, inventory and save boundary, join/quit, multi-server, partial stacks, transformation/sale paths. Acceptance: no silent loss/duplicate; unresolved windows visibly `needs_review`; production auto-delivery remains disabled until the inventory guarantee and manual procedure are reviewed.
3. **Creepy bridge:** scoped SELECT views, readiness, bounded poll, validated console dispatch, SQLite retries/outbox/ack, current-link checks and changelog. Tests: offline/start-after-expiry, revoked link, fake fields, retries, outage and lost ack. Acceptance: no direct public ports, no duplicate charge; final state reconciles after restart.
4. **Worker/D1:** additive migrations, auth/CSRF/mandatory Turnstile, rate buckets, indexed queue/status/ack and catalog, liveness/expiry semantics, measured D1 cost and flag. Tests: authorization, CSRF, Turnstile failure, no cross-user read, concurrency, stale prices, duplicate and out-of-order ack, query plans + row metrics. Acceptance: bounded costs under budget and no D1 monetary authority.
5. **Website player UX:** catalog, odds, 1/10 consent, bounded polling, accessibility, reveal/sort/SSR+ and recovery. Tests: offline/error/pending, repeated clicks/tabs, 1/10 with multiple SSR+, no invented results, mobile and keyboard. Acceptance: all results correspond exactly to the committed plugin draw; no paid path.
6. **Admin editors:** item/satchel proposal, plugin validation and versioned publish, safe simulation/test mode. Tests: non-admin/stale rank and concurrent edit, unsafe NBT/enchants/attributes, overflow, schedule boundaries/DST, malicious text, invalid ability, test isolation, deterministic server-side value computation and its caps, tier-band and ability-gate blocks, EV/balance thresholds, SSR+ frequency, value concentration, ignored client-sent value or charge fields. Acceptance: only authorized, validated versions go live; preview has no side effects.
7. **Ability wave 1:** registry and eight individually feature-flagged handlers, ideally split into small sub-PRs grouped by risk. Tests for cooldown persistence, cancellation, protection and performance, rejoin, malicious tags and anti-OP limits, charge accounting exactly once per applied effect with restore on cancel, all three depletion variants and their idempotency, no refill via anvil/grindstone/Mending/enchanting/crafting/smithing, stacked-item rejection, restart persistence, plus authenticated in-game tests in a safe environment. Acceptance: each ability independently enabled only after safety and balance review; failing light/lumberjack isolation leaves those disabled.

Future deploys require approved backup, review/CI, standard CraftCore procedure and Nils's explicit OK before recreating services; this document performs none of those actions. Website design PRs must follow the existing mockup comparison process, without removing features or weakening tests.

## Risks and questions for Nils

1. Please supply the actual list of the remaining 17 proposed abilities: the delivered static mockup contains only a dynamic placeholder, not their names. Confirm whether all 25 should eventually be included.
2. What are the initial CP prices, seven exact tier odds and item quantities for each Beutel/theme? Is `S` initially empty in any legacy pool? Please approve the resource resale/cap and bound-item policies before implementation.
3. Which timezone and daily/weekly boundary apply to rotations (including DST), and which weekly Ninja dates and building schedule should launch?
4. Approve/tune the proposed eight ability bounds and the rules for claims, PvP/combat, cooldowns and teleport costs. `miners_light` and `lumberjack` are conditional on proving safe implementation.
5. Is the explicit `needs_review` path acceptable for unavoidable inventory/DB crash ambiguity, with admin reconciliation rather than automatic duplicate or silent loss? No claim of unconditional exactly-once delivery is made.
6. Confirm admin identity source/permissions and whether a genuinely isolated test context exists; otherwise test button remains disabled. Approve the D1 feature budget and liveness deadline after whole-site measurements.
7. Approve or correct the value model (§5.1): the `B`/`E`/`A`/`T` weights, the tier `W` bands, the ability minimum tiers and the `EV` zones. Are the example costs (150 / 200 / 350 / 450 / 500 CP) and pools in §5.2 close to what you want? Should `EV/cost` below 0.40 be a hard block too, or stay a warning with your approval?
8. Confirm the ability limits (§6.1): `ore_sense` 4 charges (range 3–5), `mine_escape` 3, `homeward` 3 — and should utility items keep the base item (`ability_lost`) or be destroyed (`item_breaks`) when used up? Is `miners_light` acceptable as the only ability with neither a cooldown nor charges?
