# Economy Bounties

A data-driven bounty and commission framework for Minecraft, designed as an addon for [Economy](https://github.com/UpperMoon0/Economy).

The bounty engine owns gameplay concepts such as groups, tiers, weighted deterministic generation, progression eligibility, objectives, rotations, cooldowns, completion tracking, persistence and rewards. Economy remains responsible for accounts, currency, transactions and market infrastructure.

## Current implementation

The first implementation is deliberately loader-neutral and lives in `common` so the gameplay model can be shared unchanged across Minecraft versions and loaders.

Implemented now:

- stable namespaced public identifiers with no Minecraft-version type leakage
- data-driven immutable bounty definitions
- group + min/max progression eligibility
- weighted deterministic selection by world seed, player UUID, group, rotation epoch and reroll ordinal
- deterministic objective quantities and currency reward ranges
- recent-history suppression and per-definition cooldowns
- explicit `OFFERED -> ACTIVE -> COMPLETED -> CLAIMED` state machine with expiry
- extensible `ProgressionProvider`, `RewardProvider`, `ObjectiveType` and `BountyStateStore` APIs
- built-in event types for item/fluid delivery, kills, crafting, mining and location visits
- idempotent payout keys based on bounty instance UUIDs
- immutable player-state snapshots for loader persistence
- JSON definition codec and example data
- JUnit coverage and GitHub Actions CI

The Minecraft loader modules and direct Economy adapter are intentionally kept outside the core contract. The Economy adapter will consume only Economy's stable public API once that API lands; it must not import Economy `core`, `trading`, `data` or loader implementation packages.

## Definition format

Example:

```json
{
  "id": "bounty_harvest:carrot_delivery",
  "group": "economy_bounties:farming",
  "min_level": 5,
  "max_level": 20,
  "weight": 8,
  "offer_duration_seconds": 1800,
  "cooldown_seconds": 3600,
  "objectives": [
    {
      "type": "economy_bounties:deliver_item",
      "target": "minecraft:carrot",
      "amount": { "min": 48, "max": 80 }
    }
  ],
  "reward": {
    "currency": { "min": "160.00", "max": "230.00" },
    "metadata": { "funding": "treasury" }
  },
  "tags": ["farming", "early_game"]
}
```

A loader adapter should discover bounty JSON from datapack resources, decode it with `BountyJsonCodec`, validate every objective against the registered `ObjectiveRegistry`, and atomically replace the active definition catalog on reload.

## Integration contract

A progression addon supplies `ProgressionProvider.level(player, group)`. Minecraft loader code translates real gameplay into `ProgressEvent`s. A reward integration implements `RewardProvider` and must treat `RewardContext.payoutKey()` as an idempotency key.

For Economy, the intended transaction cause is `economy_bounties:bounty_reward`, with bounty/group/instance identifiers copied into structured transaction metadata. Servers may choose a mint-backed provider or a treasury-backed provider without changing the bounty engine.

See [`docs/architecture.md`](docs/architecture.md) for the boundary rules.
