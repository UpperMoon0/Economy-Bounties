# Architecture

Economy Bounties is layered deliberately:

- **Economy** owns money: accounts, transfers and transaction records.
- **Economy Bounties common** owns bounty rules: definitions, pools, progression eligibility, deterministic selection, objectives, generated/player-posted state machines and persistence contracts.
- **Minecraft integration** owns authoritative gameplay adapters, datapack reload, world persistence, Economy wiring, commands and the bounty-board server controller.
- **OpenUI client** renders server snapshots and sends intent packets. It does not own authoritative bounty or money state.
- **Content addons/modpacks** own concrete bounty content and progression integrations.

The common engine is pure Java and has no Minecraft or Economy implementation dependency. Minecraft adapters translate gameplay/datapack resources into the common API. Economy payout and escrow integration is isolated behind `RewardProvider` / `EscrowProvider`, while `EconomyMoneyAdapter` depends only on Economy's supported public API.

## Server-authoritative bounty board

`/bounties` opens an OpenUI screen from a server-produced `BoardSnapshot`. The protocol is deliberately small: the client sends `BoardRequest` intent such as refresh, roll, accept, cancel, claim, create or deliver. The packet context supplies the acting player; player UUIDs, progress values and payment results are never accepted from the client.

Before mutation, `BountyBoardServer` re-resolves the referenced contract and validates state, ownership/claimant, audience and request bounds. Posted-bounty forms also have objective/audience/outstanding-contract/lifetime limits. Board JSON payloads are size-bounded before parsing.

OpenUI is a client-only runtime dependency. Common networking registration avoids resolving the client screen class, so dedicated servers can load Economy Bounties without OpenUI classes present. Client setup binds the S2C snapshot callback and registers the physical-client receiver.

Minecraft 1.20.1 uses Architectury's legacy `FriendlyByteBuf` receiver registration. Minecraft 1.21.1 and 26.1.2 use typed custom payloads behind version-local `NetworkChannel` facades. The gameplay protocol remains version-neutral.

## Progress semantics

Normal gameplay events such as kills, mining and crafting are intentionally shareable: one legitimate gameplay action may advance every active matching bounty.

Finite-resource delivery is different. A delivery request identifies the exact **source + bounty UUID + objective index**. The server verifies that exact objective, computes its remaining amount and consumes matching inventory before emitting progress. `ProgressScope` carries the exact contract/objective identity through the common engine so the same consumed stack cannot advance a second bounty or a duplicate objective on the same bounty.

`deliver_item` consumes matching item stacks directly from the claimant's server inventory. `deliver_fluid` currently uses matching bucket items, credits 1000 fluid units per bucket and returns empty buckets.

## State and recovery

Generated bounty state is persisted per player through `BountyStateStore` snapshots.

Player-posted contracts use explicit in-flight money states so disk state is committed before an external transfer is attempted:

- `FUNDING -> OPEN`
- `COMPLETED -> PAYING -> CLAIMED`
- `OPEN -> CANCELLING -> CANCELLED`
- `OPEN/ACTIVE -> EXPIRING -> EXPIRED`

On server load/maintenance, pending funding, payout and refund states are retried. Economy operation identifiers are deterministic per bounty operation, allowing the Economy adapter to recognize already-recorded transfers when recovery retries an operation.

World data is stored under the world's Economy Bounties data directory and is loaded only after the server/world is available. Datapack reload validates all bounty/objective definitions before replacing the active catalog.

## Determinism

Generated offers derive their PRNG seed from world seed, player UUID, group/pool id, rotation epoch and reroll ordinal. Restarting or relogging therefore does not change the result for the same roll inputs. Servers may intentionally advance the rotation epoch to rotate offers.

## Extensibility

Public extension points include:

- `ProgressionProvider` — supplies effective player level/rank for a bounty group.
- `RewardProvider` — pays generated bounty rewards using an idempotent payout key.
- `EscrowProvider` — funds, pays and refunds player-posted bounty escrow.
- `ObjectiveType` / `ObjectiveRegistry` — validates objective definitions and converts gameplay progress events into deltas.
- `BountyDefinitionSource` — supplies data-driven generated bounty definitions.
- `BountyStateStore` / `PostedBountyStore` — durable generated and posted state persistence.

Objective, progress-scope and reward metadata is namespaced and immutable at API boundaries.

## Economy integration

Economy Bounties requires Economy **0.0.12 or later**. `EconomyMoneyAdapter` uses only the supported top-level `com.nstut.economy.api` package, including `EconomyApi`, account interfaces, namespaced transaction causes and structured transaction metadata. It must never import `com.nstut.economy.api.internal`, `com.nstut.economy.core`, `trading`, `data`, or loader-specific implementation packages.

Runtime metadata uses 0.0.12 as the minimum version and accepts later compatible releases.

CI checks forward compatibility against current `Economy/main` without granting Bounties source-level access to Economy internals: CI publishes Economy's public Maven artifacts to the local repository first, verifies the expected artifacts exist, and then resolves/tests/builds Economy Bounties through those coordinates. This catches both stable-API drift and broken transitive publication metadata.
