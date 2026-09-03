# Economy Bounties

A data-driven bounty and commission addon for Minecraft, built on [Economy](https://github.com/UpperMoon0/Economy) with an [OpenUI MC](https://github.com/UpperMoon0/OpenUI-MC) client interface.

Economy Bounties owns bounty gameplay: groups, tiers, deterministic generation, progression eligibility, objectives, player-posted contracts, escrow lifecycle, completion tracking, persistence and rewards. Economy remains responsible for accounts, currency and transactions.

## Playable flow

Run `/bounties` to open the server-backed OpenUI bounty board.

The board supports:

- **Generated bounties** — browse configured pools, roll deterministic offers, accept/cancel contracts, track objectives and claim Economy rewards.
- **Player-posted bounties** — create and fund contracts, choose one or more objectives, set a lifetime and audience policy, accept eligible contracts, complete them and collect escrowed rewards.
- **Delivery objectives** — `deliver_item` consumes matching items from the claimant's real server inventory; `deliver_fluid` consumes matching fluid buckets and returns empty buckets.
- **Gameplay objectives** — built-in adapters record entity kills, block mining, item crafting and location/dimension visits from server-side gameplay events.
- **Crash-safe money operations** — posted funding, payout and refund states persist before Economy transfers and recover safely after restart.

The client never supplies player identity, progress counters or payout results. Board packets are intent-only; the server re-reads the selected bounty and validates its current status, claimant/creator, audience, objective, inventory and Economy operation before mutating state.

Consumable delivery is scoped to an exact **bounty UUID + objective index**. Delivering one stack therefore cannot advance another active bounty—or another identical objective on the same bounty—with the same target.

## Supported targets

- Fabric 1.20.1
- Forge 1.20.1
- Fabric 1.21.1
- NeoForge 1.21.1
- NeoForge 26.1.2

Runtime metadata requires **Economy 0.0.12 or later**. OpenUI MC is a required **client-side** dependency and is not required on a dedicated server.

## Engine and extension points

The loader-neutral `common` module provides:

- stable namespaced public identifiers with no Minecraft-version type leakage
- immutable bounty/objective/reward definitions with tiers and min/max progression levels
- weighted bounty pools that choose an eligible group and then a weighted bounty inside it
- deterministic selection from world seed, player UUID, group/pool, rotation epoch and reroll ordinal
- deterministic objective quantities and currency reward ranges
- recent-history suppression and per-definition cooldowns
- generated `OFFERED -> ACTIVE -> COMPLETED -> CLAIMED` lifecycle with expiry/cancellation
- durable player-posted funding/open/active/completed/paying/cancelling/expiring/terminal states
- extensible `ProgressionProvider`, `RewardProvider`, `EscrowProvider`, `ObjectiveType`, `ObjectiveRegistry`, `BountyStateStore` and `PostedBountyStore` APIs
- built-in objective identifiers for item/fluid delivery, kills, crafting, mining and location visits
- scoped progress metadata for finite-resource objectives while ordinary gameplay events remain shareable across matching active contracts
- deterministic payout/escrow operation identifiers
- JSON codecs for bounty definitions, weighted pools and board protocol payloads
- bounded network payload parsing and server-side request validation

The common engine remains independent of Minecraft and Economy implementation packages. Minecraft-facing integration targets Economy 0.0.12+ exclusively through the supported top-level `com.nstut.economy.api` surface; it must not import Economy `core`, `trading`, `data`, `api.internal` or loader implementation packages.

## Definition format

Example generated bounty:

```json
{
  "id": "bounty_harvest:carrot_delivery",
  "group": "economy_bounties:farming",
  "tier": 2,
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

A board/guild/NPC can define a weighted group pool independently:

```json
{
  "id": "bounty_harvest:village_board",
  "groups": [
    { "id": "economy_bounties:farming", "weight": 5 },
    { "id": "economy_bounties:mining", "weight": 3 },
    { "id": "economy_bounties:hunting", "weight": 2 }
  ]
}
```

The engine discards groups that have no level-eligible, non-cooled-down definition for the player before applying group weights. Recent-history suppression is then applied across the eligible pool, avoiding high-weight but unavailable groups swallowing rolls.

Datapack resources are decoded with `BountyJsonCodec` / `BountyPoolJsonCodec`, every objective is validated against the registered `ObjectiveRegistry`, and reload atomically replaces the active definition/pool catalog.

## Economy integration contract

A progression addon supplies `ProgressionProvider.level(player, group)`. Minecraft integration translates authoritative gameplay into `ProgressEvent`s. A reward integration implements `RewardProvider` and must treat `RewardContext.payoutKey()` as an idempotency key.

The built-in Economy adapter uses Economy 0.0.12+'s stable public API. Bounty transactions use namespaced causes such as `economy_bounties:bounty_reward`, with bounty identifiers and operation identifiers copied into structured transaction metadata. Generated rewards may be mint-backed or treasury-backed; player-posted bounty funds use the Economy server account as their transfer boundary.

CI publishes current `Economy/main` to Maven local first and then resolves Economy Bounties through the public Maven coordinates rather than Gradle composite substitution. That intentionally catches missing/transitive artifact and stable-API compatibility problems that an external addon consumer would hit.

See [`docs/architecture.md`](docs/architecture.md) for boundary rules and persistence/recovery details.
