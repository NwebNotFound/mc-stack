# Changelog

## Follow-up

- Pin CI actions, restrict workflow permissions, and verify the vendored VaultAPI jar.
- Batch sell-menu price previews and prevent repeat refunds for delivered sales.

## PR #28

- Backfill death stats path; add the `v_economy_daily` view and `creepy_ro` grant.
- Add the cloudflared tunnel profile.
- Add sell transaction idempotency and commit-status checks, with per-stack asynchronous selling.
- Add `REFUND_OWED` recovery and `sell_pending`.
- Add `market.sell.enabled` and `sell-menu.enabled` flags, both defaulting to false.
- Add CI.
