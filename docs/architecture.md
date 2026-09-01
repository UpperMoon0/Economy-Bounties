# Architecture

Economy Bounties is layered deliberately:

- **Economy** owns money: accounts, transfers, transaction history, market infrastructure.
- **Economy Bounties** owns bounty gameplay: definitions, groups, progression eligibility, deterministic weighted selection, objectives, player state, cooldowns, completion and reward dispatch.
- **Content addons/modpacks** own concrete bounty content and progression integrations.

The common bounty engine is pure Java and has no Minecraft or Economy implementation dependency. Loader adapters are expected to translate Minecraft events/datapack resources into the common API. Economy payout integration is isolated behind `RewardProvider` so the addon never needs to depend on Economy internals.

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

When Economy's stable API work lands, the Economy adapter should use only `com.nstut.economy.api` and a namespaced transaction cause such as `economy_bounties:bounty_reward`. It should never import `com.nstut.economy.core`, `trading`, `data`, or loader-specific implementation packages.
