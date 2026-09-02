# Architecture

Economy Bounties is layered deliberately:

- **Economy** owns money: accounts, transfers, transaction history, market infrastructure.
- **Economy Bounties** owns bounty gameplay: definitions, groups, progression eligibility, deterministic weighted selection, objectives, player state, cooldowns, completion and reward dispatch.
- **Content addons/modpacks** own concrete bounty content and progression integrations.

The common bounty engine is pure Java and has no Minecraft or Economy implementation dependency. Loader adapters translate Minecraft events/datapack resources into the common API. Economy payout and escrow integration is isolated behind `RewardProvider` / `EscrowProvider`, while the Minecraft-facing adapter depends only on Economy's supported public API.

## Determinism

Offers derive their PRNG seed from a world seed, player UUID, group id, rotation epoch, and reroll ordinal. Restarting or relogging therefore does not change an offer. Servers may intentionally advance the rotation epoch to rotate offers.

## Extensibility

The initial public extension points are:

- `ProgressionProvider` — supplies effective player level/rank for a bounty group.
- `RewardProvider` — pays a completed bounty and supplies an idempotent payout result.
- `ObjectiveType` / `ObjectiveRegistry` — validates objective definitions and converts gameplay progress events into progress deltas.
- `BountyDefinitionSource` — supplies data-driven bounty definitions.

Objective and reward metadata is namespaced and immutable at API boundaries.

## Economy integration

Economy Bounties requires Economy 0.0.12 or later. `EconomyMoneyAdapter` uses only the supported top-level `com.nstut.economy.api` package, including `EconomyApi`, account interfaces, namespaced transaction causes and structured transaction metadata. It must never import `com.nstut.economy.api.internal`, `com.nstut.economy.core`, `trading`, `data`, or loader-specific implementation packages.

CI checks the addon against the current `Economy/main` source through Gradle composite-build substitution. Runtime metadata uses 0.0.12 as the minimum version, allowing later compatible Economy releases.
