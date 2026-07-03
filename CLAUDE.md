# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this service is

Market State Service consumes canonical **depth-diff** events from Kafka, reconstructs a per-symbol L2 order book using a Binance Spot **snapshot + diff** bootstrap, and publishes **trusted** market state for downstream consumers. BBO and top-N depth are published together, atomically, inside a single authoritative snapshot event (they are projections of the same reconstructed book version). A separate status event reports lifecycle/quality transitions (resync, crossed book, gaps, …). The service does not connect to exchanges, normalize raw feeds, or make trading decisions.

- Input topic: `canonical.market.depthdiff.v1`
- Output topics: `market.state.orderbook.l2.snapshot.v1` (authoritative `OrderBookL2SnapshotEvent` — metadata + version + quality + bbo + depth + source), `market.state.orderbook.status.v1` (`OrderBookStatusEvent` — lifecycle/sync status + reason). Both are keyed by `metadata.instrumentId`. A crossed/out-of-sync book is **never** published as a snapshot; it is reported via a status event instead.

`market-state-service-technical-design.md` is the authoritative spec for the domain (state machine, bootstrap/live sequencing rules, invariants, output contracts). **`README.md` is the upstream hexagonal-template README and does NOT describe this service** (it talks about an `Item` domain, REST API, Postgres/Flyway — none of which exist here). Trust the design doc and the code, not the README.

## Commands

The Gradle wrapper is the entry point. On Windows use `.\gradlew.bat`; the Bash tool can use `./gradlew`.

```powershell
.\gradlew.bat build                    # compile + test all modules
.\gradlew.bat test                     # run all tests
.\gradlew.bat :application:test        # test a single module
.\gradlew.bat :infrastructure:app:bootRun   # run the service
.\gradlew.bat clean
```

Run a single test class or method (JUnit 5 platform):

```powershell
.\gradlew.bat :application:test --tests "com.trading.mss.service.DepthDiffServiceTest"
.\gradlew.bat :application:test --tests "com.trading.mss.service.DepthDiffServiceTest.Bootstrap.successfulBootstrap_goesLive"
```

Local infrastructure (Kafka in KRaft mode, Confluent Schema Registry on :8081, Kafka UI on :8088):

```powershell
docker compose up -d
```

### Required external dependencies (mavenLocal)

The build resolves two first-party artifacts from `mavenLocal()` — they are **not** in this repo and must be `mvn install`ed locally first or the build fails to resolve:

- `com.trading:trading-common:1.0.0-SNAPSHOT` — shared enums/event-type constants (e.g. `com.trading.common.enums.BookSyncStatus`).
- `com.trading:trading-schemas:1.0.0-SNAPSHOT` — generated Avro classes for Kafka (e.g. `com.trading.contracts.market.DepthDiffEvent`).

## Architecture

Gradle multi-module, hexagonal (ports & adapters). Group `com.trading`, base package `com.trading.mss`, Java 21.

| Module | Role |
|--------|------|
| `application` | Domain + use cases. **Plain `java-library`, no Spring.** Depends only on `trading-common`, slf4j, lombok. All correctness logic lives here. |
| `infrastructure/app` | Spring Boot entrypoint + **all bean wiring** (`InfrastructureConfig`). Holds the in-memory state store. |
| `infrastructure/kafka-adapter` | Kafka consumer + publishers, Avro (de)serialization, Schema Registry config. |
| `infrastructure/binance-snapshot-adapter` | `RestClient`-based Binance REST snapshot loader. |

### Wiring model — read this before adding a class

The `application` module classes are **not** Spring components — they have no `@Component`/`@Service` annotations. They are instantiated and wired **manually** as `@Bean` methods in [InfrastructureConfig.java](infrastructure/app/src/main/java/com/trading/mss/config/InfrastructureConfig.java). When you add an application service, add a `@Bean` for it there and thread its dependencies through.

The adapter modules *do* use `@Configuration`/`@Bean` classes (e.g. `KafkaConsumerConfig`, `KafkaProducerConfig`) — these are picked up by component scan because `Application` lives at `com.trading.mss` and every module shares that base package.

### Identity model

The primary MSS identity is the canonical **`instrumentId`** (e.g. `BINANCE|SPOT|BTC|USDT`), carried by [InstrumentKey](application/src/main/java/com/trading/mss/domain/model/InstrumentKey.java): `canonical()` = `instrumentId`, used for the state-store map key, dispatcher stripe hashing, and the Kafka key of every published snapshot/status event. `exchange`/`marketType`/`symbol` are **routing attributes, not identity** — the native symbol (`BTCUSDT`) drives the Binance REST snapshot API and sync-policy selection, and all three appear in metadata/logs, but never in map keys or stripe hashes. **There is no fallback identity mode**: an event with a blank `instrumentId` (or blank routing attributes) is invalid — the consumer skips it and publishers refuse to publish under a symbol key. `instrumentId` comes from the upstream canonical event; MSS never reconstructs it. `SymbolState`'s identity fields (`instrumentId`, `venue`, `marketType`, `symbol`) are immutable, set at creation from the `InstrumentKey`.

### Command dispatch model — the serialization invariant

All `SymbolState` mutation happens inside **serialized per-instrument commands** submitted through `SymbolExecutorPort`, implemented by [StripedSerialExecutor](infrastructure/app/src/main/java/com/trading/mss/dispatch/StripedSerialExecutor.java) (N stripes, default 1 = a global event loop; an instrument is pinned to a stripe by `InstrumentKey.canonical()` = `instrumentId` hash). Three command sources:

1. **Kafka**: `DepthDiffConsumer` validates, deserializes and enqueues `processor.process(dto, ctx)` — blocking `put` (backpressure). Offsets commit after enqueue, not after processing; acceptable because state is in-memory and rebuilt via bootstrap after restart.
2. **Snapshot callbacks**: `DepthDiffBootstrapService.startBootstrapIfNeeded` attaches `whenCompleteAsync(..., executorFor(key))` — the fetch result is applied the moment it arrives (`onSnapshotReady`), guarded by `bootstrapEpoch` + `SNAPSHOT_LOADING` against superseded fetches. There is no polling.
3. **Watchdog ticks**: `SymbolStateWatchdog` (`@Scheduled`, 1s) submits `SymbolTickService.onTick(key)` via non-blocking `tryExecute` (droppable). Ticks restart bootstrap after the cooldown (even with an empty buffer), time out lost snapshot fetches, and enforce two-tier staleness: soft (status event, `trusted` unchanged) then hard (full resync — a dead stream degrades into controlled REST polling).

Rules that follow: never touch a `SymbolState` outside its command (the store's `keys()` deliberately returns only immutable `InstrumentKey`s); never pass a `SymbolState` reference across an async boundary — pass the `InstrumentKey` and re-resolve inside the command; when a command running on a stripe submits a follow-up to the same stripe, the executor routes it through a local deque (never blocks on its own queue). The dispatcher is a `SmartLifecycle` at phase 0 so Kafka containers stop first and accepted commands drain on shutdown.

### Core flow: per-symbol state machine via handler registry

The central use case is `DepthDiffProcessor.process(DepthDiffDto, KafkaMessageContext)`, implemented by [DepthDiffService](application/src/main/java/com/trading/mss/service/DepthDiffService.java). It:

1. loads/creates the `SymbolState` for the event's `InstrumentKey` (identity = `instrumentId`; skips the event on an identity/routing mismatch),
2. dispatches to a `DepthDiffStateHandler` chosen by the current `SymbolStateStatus`.

`DepthDiffStateHandlerRegistry` is an `EnumMap<SymbolStateStatus, DepthDiffStateHandler>`. Status → handler mapping:

- `INIT` → `InitDepthDiffStateHandler`
- `BUFFERING_DIFFS` → `BufferingDiffsStateHandler`
- `SNAPSHOT_LOADING` **and** `APPLYING_BUFFER` → `BootstrapPhaseStateHandler` (the `APPLYING_BUFFER` mapping is added via `registerAdditionalStatus`, since a handler declares only one `supportedStatus()`)
- `LIVE` → `LiveDepthDiffStateHandler`
- `RESYNCING` → `ResyncingDepthDiffStateHandler`

The bootstrap/live/resync algorithms (snapshot staleness check, bridging-event check `snapshotLastUpdateId ∈ [U;u]`, live `IGNORE`/`APPLY`/`RESYNC` decision on `U`/`u`/`localUpdateId`) are implemented by `DepthDiffBootstrapService`, `LiveOrderBookUpdateService`, and `BinanceSpotSyncPolicy`. See design doc §8–§9 for the exact rules these enforce.

### Conventions that matter for correctness

- **Per-instrument sequential processing is required.** Sequence checks (`U`, `u`, `localUpdateId`) are only valid if one instrument is never mutated concurrently. The dispatcher (see above) is the enforcement mechanism; the input topic must still be keyed by `instrumentId` so enqueue order matches offset order. A full stripe queue blocks the Kafka listener — watch `mss.dispatcher.enqueue.blocked.time`; a sustained block risks `max.poll.interval.ms` (consumer pause/resume is a known future refinement).
- **Prices and quantities are scaled `long`s, not `BigDecimal`/`double`.** [ScaledDecimal](application/src/main/java/com/trading/mss/domain/model/ScaledDecimal.java) converts to/from strings using a fixed scale of `1e8` (8 digits). The `OrderBook` stores `NavigableMap<Long, Long>` (bids descending, asks ascending). Use `ScaledDecimal.parse`/`format` at the boundaries; keep the book in scaled longs.
- **State is in-memory only** (`InMemorySymbolStateStore`). On restart, state is rebuilt from snapshot + buffered diffs — there is no persistence layer.
- **DTOs are Java records**; Avro ↔ DTO mappers in the kafka-adapter are static utility classes (e.g. `DepthDiffAvroMapper.toDto`). The domain never touches Avro types directly.
- **Kafka uses Avro + Confluent Schema Registry**, not JSON. Consumer/producer factories set `KafkaAvroDeserializer`/serializer with `specific.avro.reader=true`.

### Configuration

Runtime config is in [application.yml](infrastructure/app/src/main/resources/application.yml), all overridable via `APP_*` env vars: Kafka bootstrap/group, Schema Registry URL, in/out topic names, Binance REST base URL + snapshot depth limit (`1000`), max buffered events (`10000`), published top-N depth (`10`), dispatcher stripes/queue capacity (`1`/`10000`), watchdog interval (`1000`), snapshot timeout (`60000` — must exceed the fetch worst case, validated with a WARN at wiring), and staleness soft/hard thresholds (`30000`/`120000`).
