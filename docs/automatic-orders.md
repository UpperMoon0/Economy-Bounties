# Automatic Orders — implementation and datapack guide

Automatic Orders replace player-initiated system bounty rolling. Accepted automatic orders appear in **Contracts**, while player-funded **Posted** contracts remain separate. A server maintains the rotating board slots; the player only selects an offered order. Accepting creates a normal generated bounty with the existing item-consumption, progress, Economy payout and idempotent claim flow.

## Player experience

- **Orders** is the initial tab when opening the Bounty Board block or `/bounties`.
- **All / Personal / Global** scopes are independent of **Normal / Special** type tags. A special may be personal or global. Search, status, sort, Ready-only, and Grid/List controls refine the browser without requesting new server-generated offers.
- **Personal** offers are per-player and can be discarded for a cooldown. **Global** offers are server-wide and can be claimed by exactly one player; stale attempts fail. **Daily Special** and **Harvest Festival** demonstrate recurring daily and bounded event pools.
- Each available order previews the number of matching items currently in the player's server inventory. When the entire delivery is in the bag, **Deliver now** completes acceptance, scoped inventory consumption, and payout in one click. If short on items, **Accept** tracks the job under **Contracts**. A payout failure after delivery remains retryable in **Contracts**.
- Accept/Deliver sends the board ID, slot index, and current offer UUID. The server checks all three against persisted state. No client-supplied inventory counts, reward amounts or completed objectives are trusted.
- A slot is **not** restocked when the player accepts. It restocks after the accepted contract is *claimed, cancelled or expired* and the configured cooldown passes.
- The UI requests a refreshed server snapshot every 20 seconds while open. All deadlines and restock decisions are validated from the server clock.

## Datapack configuration

Place one JSON file under `data/<namespace>/order_boards/<name>.json`. Four bundled examples are under `common/src/main/resources/data/economy_bounties/order_boards`.

```json
{
  "id": "my_pack:weekly_supplies",
  "scope": "personal",
  "slots": 6,
  "restock_seconds": 300,
  "rotation_seconds": 86400,
  "allow_discard": true,
  "minimum_reward": "5.00",
  "maximum_reward": "1000.00",
  "inputs": [
    {"item": "minecraft:wheat", "quantity": {"min": 16, "max": 32},
     "weight": 8, "reference_price": "2.50", "min_level": 0}
  ],
  "rewards": [
    {"id": "standard", "title": "Merchant order", "rarity": "Common",
     "weight": 80, "multiplier": "1.25", "bonus": "5.00",
     "timer": "none", "duration_seconds": 3600},
    {"id": "rush", "title": "Urgent dispatch", "rarity": "Rare",
     "weight": 20, "multiplier": "2.00", "bonus": "25.00",
     "timer": "offer", "duration_seconds": 1800}
  ]
}
```

**Scope:** `personal` generates an independent board for each player. `shared` generates one global first-come board. Shared boards cannot be discarded by an individual player.

**Timers:** `none` means no meaningful delivery deadline after acceptance (a bounded 10-year engine expiry is used); the *offer* still rotates according to `rotation_seconds`. `offer` starts the expiration clock when the offer first appears. `accept` starts the delivery deadline when it is accepted, while the unaccepted offer still rotates normally. In every case expired/rotated offers cannot be accepted.

**Schedules:** Optional `active_from_epoch` (inclusive) and `active_until_epoch` (exclusive) bound a whole event board in UTC epoch seconds. Each reward tier may supply `start_hour_utc` and `end_hour_utc` for a daily availability window. Both values 0 make a tier eligible all day. The generator excludes ineligible tiers instead of delaying claims; already accepted work remains in the Contracts tab when an event ends.

**Input and reward independence:** The server first chooses one weighted eligible input, then one weighted eligible reward tier. Quantities are sampled inside the configured range. Define broad pools rather than duplicating hundreds of cross-product templates.

**Pricing:** `raw = quantity × unit_quote × multiplier + bonus`, rounded to cents and clamped to the board minimum/maximum. `unit_quote` uses the Economy Market's read-only quote when available, otherwise `reference_price`. Quotes are bounded to 0.5–2 × reference, with absurd/unavailable values falling back to reference. Configure the reference prices conservatively: individual players can manipulate market asks/bids.

**Validation bounds:** 1–12 slots, up to 128 inputs/64 reward tiers, 1–4096 items per order, positive weights and prices, 5-second minimum restock, 1-minute minimum rotation, bounded payout maximum (at most 1,000,000 currency), and 24 active/completed/unclaimed contracts per player before accepting more. Do not scale these limits casually.

## Persistence and safety

The board state lives in the world folder under `data/economy_bounties/order_boards/<board_id>/<owner_uuid>.json`; shared boards use `shared.json`. Writes use an atomic replace when supported by the filesystem. The generated bounty lives separately in the existing per-player bounty store.

Acceptance uses a write-ahead **claimant lock**: save the slot claimant first, grant a server-derived bounty using a deterministic instance ID, and mark the grant complete. On restart a pending grant is reconciled for the original claimant; another player cannot steal the locked offer. An expired pending timed claim is released after cooldown if no bounty was granted. The Economy operation remains idempotent and delivery consumes only the exact matching inventory and objective.

Admin datapacks define the items, relative rarity, prices, and active windows; packet clients cannot author them. Avoid disabling atomic file writes or deleting the world-bound order state while a server is running. Keep world backups before changing pack contents.

## Testing

The loader-neutral tests include automatic fills without manual rolls, deterministic persistence after restart, shared first-claim exclusion, stale click rejection, bounded market quotes, payout idempotency, cooldowns, event windows, interrupted-claim recovery and timed claim expiry. Run `:common:test :forge-1.20.1:build` for the primary Forge target. Validate supported Fabric/NeoForge loader builds against the exact published Economy and OpenUI minimum dependencies; do not rely on GitHub Actions.
