# Changelog

## Für Spieler

- Du verkaufst jetzt Stack für Stack statt alles auf einmal.
- Wenn du während eines Verkaufs offline gehst, bekommst du deine Items beim nächsten Einloggen zurück.
- Im Verkaufsmenü siehst du vorher, wie viel du für deine Items bekommst.
- Verkaufen ist vorerst ausgeschaltet. Wir sagen Bescheid, sobald es wieder geht.

## Admin

### PR #30

- Pin CI actions, restrict workflow permissions, and verify the vendored VaultAPI jar.
- Batch sell-menu price previews and prevent repeat refunds for delivered sales.

### PR #28

- Backfill death stats path; add the `v_economy_daily` view and `creepy_ro` grant.
- **Breaking Change:** the Docker Compose profile `cloudflare` was renamed to `tunnel`. `--profile cloudflare` no longer starts anything; use `--profile tunnel`.
- Add sell transaction idempotency and commit-status checks, with per-stack asynchronous selling.
- Add `REFUND_OWED` recovery and `sell_pending`.
- Add the `market.sell.enabled` flag, defaulting to false.
- Add CI.
