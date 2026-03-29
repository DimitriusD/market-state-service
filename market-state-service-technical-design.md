# Market State Service — Technical Design

## 1. Purpose

**Market State Service** transforms raw market depth updates into a **reliable local market state**.

Input:
- `canonical.market.depthdiff.v1`

Output:
- `state.orderbook.l2.topn.v1`
- optionally `state.bbo.v1`

The service does **not** collect raw exchange data and does **not** make trading decisions.
Its responsibility is to:
- reconstruct a local L2 order book,
- guarantee sequence correctness,
- detect gaps,
- perform resync,
- publish trusted market state for downstream consumers.

---

## 2. Responsibilities

### What the service does
- consumes canonical `DEPTH_DIFF` events from Kafka,
- maintains a local L2 order book per symbol,
- performs `snapshot + diff` bootstrap,
- applies live depth diffs,
- validates sequencing,
- detects gaps and inconsistencies,
- performs resync when needed,
- calculates derived values:
  - best bid,
  - best ask,
  - spread,
  - mid,
  - top N levels,
- publishes trusted market state,
- exposes quality flags and observability data.

### What the service does not do
- open WebSocket connections to the exchange,
- normalize raw exchange messages,
- manage upstream reconnect logic,
- execute trades,
- contain strategy logic,
- act as OMS or execution engine.

---

## 3. Position in the overall system

```text
Exchange WS/REST
   ->
Market Data Service
   ->
Kafka topic: canonical.market.depthdiff.v1
   ->
Market State Service
   ->
Kafka topics:
   - state.orderbook.l2.topn.v1
   - state.bbo.v1
   ->
Signal / Strategy / Execution / Analytics
```

Key idea:
`DEPTH_DIFF` is **not** market state. It is only a stream of incremental changes.
To build reliable state, the service must combine:
- snapshot,
- buffered diffs,
- sequence checks,
- gap detection,
- resync logic.

---

## 4. Core business goal

For every `symbol`, the service maintains a state machine that:
1. buffers diffs,
2. loads a snapshot,
3. aligns snapshot with buffered events,
4. transitions to `LIVE`,
5. applies live diffs in order,
6. resyncs immediately when corruption is suspected.

---

## 5. Definition of reliable state

Reliable state does **not** mean “perfect internal copy of the exchange matching engine”.
It means:
- the local book is consistent with the feed contract,
- sequencing rules are satisfied,
- the service knows whether the state is trustworthy,
- downstream consumers receive explicit quality metadata.

`trusted=true` means at least:
- bootstrap completed successfully,
- no gaps were detected,
- `localUpdateId` is valid,
- state is not stale,
- book invariants hold.

---

## 6. Critical processing rule

**One symbol must be processed strictly sequentially.**

Therefore:
- Kafka topic must be keyed by `symbol`,
- ordering inside the symbol must be preserved,
- one symbol must not be mutated concurrently by multiple workers.

Without per-symbol ordered processing, sequence checks such as `U`, `u`, `localUpdateId` become invalid.

---

## 7. State machine per symbol

```text
INIT
  -> BUFFERING_DIFFS
  -> SNAPSHOT_LOADING
  -> APPLYING_BUFFER
  -> LIVE
LIVE
  -> RESYNCING
  -> BUFFERING_DIFFS
LIVE
  -> DEGRADED
  -> LIVE or RESYNCING
any
  -> FAILED
```

### INIT
State does not yet exist.

Properties:
- empty book,
- empty buffer,
- `trusted=false`,
- `localUpdateId=-1`.

Transition:
- first diff event -> `BUFFERING_DIFFS`

### BUFFERING_DIFFS
The service has started receiving diffs, but does not yet have a valid snapshot anchor.

Responsibilities:
- append incoming diff events to buffer,
- remember `firstBufferedU`,
- prepare snapshot load.

Risks:
- buffer growth,
- lag,
- slow snapshot retrieval.

Transition:
- start snapshot request -> `SNAPSHOT_LOADING`

### SNAPSHOT_LOADING
The service loads a REST snapshot.

Validation rule:
- if `snapshotLastUpdateId < firstBufferedU`, snapshot is too old and must be retried.

Transition:
- valid snapshot -> `APPLYING_BUFFER`
- exhausted retries -> `FAILED` or `RESYNCING`

### APPLYING_BUFFER
This is the alignment point between snapshot and buffered events.

Responsibilities:
- discard events where `u <= snapshotLastUpdateId`,
- check that the first remaining event bridges the snapshot:
  - `snapshotLastUpdateId ∈ [U;u]`,
- initialize local book from snapshot,
- apply all remaining buffered events in order.

Transition:
- success -> `LIVE`
- no bridging event -> `RESYNCING`

### LIVE
Normal operating mode.

Responsibilities:
- evaluate every diff event,
- ignore duplicates/old events,
- apply valid events,
- detect gaps,
- recalculate projections,
- publish state.

Rules:
- `u < localUpdateId` -> ignore,
- `U > localUpdateId + 1` -> gap -> resync.

### RESYNCING
Recovery mode.

Responsibilities:
- mark state as untrusted,
- reset book,
- reset buffer,
- start bootstrap again.

Transition:
- restart from `BUFFERING_DIFFS`

### DEGRADED
State still exists, but quality is reduced.

Typical reasons:
- lag is too high,
- buffer is growing too much,
- no updates for too long,
- freshness SLA is violated.

Behavior:
- continue publishing state with quality flags,
- either recover to `LIVE`,
- or transition to `RESYNCING`.

### FAILED
Serious failure state.

Examples:
- snapshot retries exhausted,
- invariant violations,
- unrecoverable internal error.

---

## 8. Bootstrap algorithm

### Goal
Build a correct local L2 book using:
- REST snapshot,
- buffered depth diffs,
- sequence checks.

### Steps
1. Start consuming diff events for the symbol and buffer them.
2. Record `firstBufferedU` from the first buffered event.
3. Load snapshot from REST.
4. If `snapshotLastUpdateId < firstBufferedU`, retry snapshot.
5. Discard all buffered events where `u <= snapshotLastUpdateId`.
6. Verify that the first remaining event bridges snapshot:
   - `snapshotLastUpdateId ∈ [U;u]`
7. Initialize local book with snapshot contents.
8. Set `localUpdateId = snapshotLastUpdateId`.
9. Apply remaining buffered events in order.
10. Transition to `LIVE`.

### Why it matters
If the service starts from the middle of the diff stream without a valid snapshot anchor, local book state can look plausible while actually being corrupted.

---

## 9. Live apply algorithm

For each incoming live diff event there are only three possible outcomes:
- `IGNORE`
- `APPLY`
- `RESYNC`

### Rules
- if `event.u < localUpdateId` -> `IGNORE`
- if `event.U > localUpdateId + 1` -> `RESYNC`
- otherwise -> `APPLY`

### Apply logic
For each price level in bids and asks:
- if `qty > 0` -> upsert level,
- if `qty == 0` -> remove level.

After successful apply:
- `localUpdateId = event.u`
- update timestamps,
- update source metadata,
- recalculate projections,
- publish state.

---

## 10. Internal data model

### DepthDiffEvent
Canonical input message must contain:
- `symbol`
- `venue`
- `firstUpdateId` (`U`)
- `finalUpdateId` (`u`)
- `bids`
- `asks`
- `exchangeEventTime`
- `ingestTime`
- `sourcePartition`
- `sourceOffset`

### OrderBookSnapshot
Must contain:
- `symbol`
- `venue`
- `lastUpdateId`
- `bids`
- `asks`
- `depthLimit`
- `loadedAt`

### OrderBook
Recommended structure:
- `bids: TreeMap<Long, Long>` descending
- `asks: TreeMap<Long, Long>` ascending

Why `TreeMap`:
- simple and reliable,
- `O(log n)` update/remove,
- easy top-N projection,
- low correctness risk.

### SymbolState
Per symbol the service stores:
- `symbol`
- `venue`
- `OrderBook`
- `status`
- `trusted`
- `localUpdateId`
- `lastSnapshotUpdateId`
- `firstBufferedU`
- buffered events
- `lastAppliedExchangeEventTime`
- `lastAppliedIngestTime`
- `sourcePartition`
- `sourceOffset`
- counters:
  - `gapCount`
  - `resyncCount`
  - `duplicateCount`
  - `snapshotRetryCount`

---

## 11. Derived state

The service must be able to produce at least:
- best bid,
- best ask,
- spread,
- mid,
- top N bids,
- top N asks.

Rules:
- `bestBid = bids.firstEntry()`
- `bestAsk = asks.firstEntry()`
- `spread = bestAsk.price - bestBid.price`
- `mid = (bestAsk.price + bestBid.price) / 2`

---

## 12. Invariants

While in `LIVE`, the service must guarantee:
1. `localUpdateId` is monotonic.
2. After apply, `localUpdateId == event.u`.
3. `bestBid <= bestAsk` when both sides exist.
4. No level with `qty == 0` remains in the book.
5. Gap is never silently ignored.
6. Downstream always sees `trusted/status`.

Violation of these invariants means the symbol is no longer healthy and must degrade, resync, or fail.

---

## 13. Output contracts

### Primary output: `state.orderbook.l2.topn.v1`
Recommended payload fields:
- `symbol`
- `venue`
- `depth`
- `bids[]`
- `asks[]`
- `version.exchangeUpdateId`
- `quality.status`
- `quality.trusted`
- `quality.snapshotDepthLimited`
- `quality.stateAgeMs`
- timestamps
- `sourcePartition`
- `sourceOffset`

### Optional output: `state.bbo.v1`
Recommended fields:
- `symbol`
- `venue`
- `bestBidPrice`
- `bestBidQty`
- `bestAskPrice`
- `bestAskQty`
- `spread`
- `mid`
- `version.exchangeUpdateId`
- `quality.status`
- `quality.trusted`

### Why snapshots instead of incremental state
Top-N snapshots are simpler and safer for downstream consumers.
They reduce the chance that different consumers implement diff apply logic differently.

---

## 14. Hexagonal architecture design

### Application core
The application module contains correctness logic:
- `OrderBook`
- `SymbolState`
- `OrderBookApplier`
- `TopNProjector`
- `BboProjector`
- `ExchangeSyncPolicy`
- `BinanceSpotSyncPolicy`
- `SymbolStateMachine`
- `ResyncCoordinator`

### Ports
Input ports:
- `ProcessDepthDiffUseCase`
- optionally `ResyncSymbolUseCase`
- optionally `GetSymbolStateQuery`

Output ports:
- `LoadOrderBookSnapshotPort`
- `PublishOrderBookStatePort`
- `PublishBboStatePort`
- `SymbolStateStorePort`
- `MetricsPort`

### Infrastructure adapters
Planned modules:
- `app`
- `kafka-adapter`
- `binance-snapshot-adapter`
- `in-memory-state-adapter`
- `observability-adapter`
- optional `rest-admin-adapter`

### Kafka adapter
One Kafka module is enough for MVP, but responsibilities must still be separated internally:
- `consumer/DepthDiffKafkaConsumer`
- `publisher/KafkaOrderBookStatePublisher`
- `publisher/KafkaBboStatePublisher`
- `config/KafkaAdapterConfig`

---

## 15. Ports and responsibilities

### ProcessDepthDiffUseCase
Main input use case.
Triggered by Kafka consumer for every canonical diff event.

### LoadOrderBookSnapshotPort
Loads snapshot from Binance REST API.

### PublishOrderBookStatePort
Publishes `state.orderbook.l2.topn.v1`.

### PublishBboStatePort
Publishes `state.bbo.v1`.

### SymbolStateStorePort
Stores runtime symbol state.
For MVP this is an in-memory adapter.

### MetricsPort
Updates metrics for transitions, lag, resync, and health.

---

## 16. Main use case flow

`process(event)` should work like this:

```text
1. Resolve processor/state for symbol
2. If state is INIT -> start bootstrap
3. If state is BUFFERING/SNAPSHOT_LOADING/APPLYING -> buffer + continue bootstrap
4. If state is LIVE -> evaluate event:
     IGNORE / APPLY / RESYNC
5. If APPLY -> mutate book, update version, project top-N/BBO, publish
6. If RESYNC -> reset state and restart bootstrap
7. Update metrics and logs
```

This is the central use case of the service.

---

## 17. Failure handling

### Gap
Condition:
- `U > localUpdateId + 1`

Action:
- immediate resync.

### Duplicate / old event
Condition:
- `u < localUpdateId`

Action:
- ignore.

### Stale snapshot
Condition:
- `snapshotLastUpdateId < firstBufferedU`

Action:
- retry snapshot.

### No bridging event
Condition:
- after discard there is no event satisfying
  `snapshotLastUpdateId ∈ [U;u]`

Action:
- resync.

### Slow consumer / lag
Symptoms:
- buffer grows,
- state age grows,
- consumer lag increases.

Action:
- transition to `DEGRADED`,
- then resync or scale processing.

### Restart
Condition:
- process restarts and loses in-memory state.

Action:
- rebuild using snapshot + buffered diffs.

---

## 18. Observability

### Metrics
Minimum required metrics:
- `market_state_status{symbol}`
- `trusted{symbol}`
- `local_update_id{symbol}`
- `gap_count_total{symbol}`
- `resync_count_total{symbol}`
- `snapshot_retry_count_total{symbol}`
- `buffered_diff_count{symbol}`
- `state_age_ms{symbol}`
- `publish_lag_ms{symbol}`
- Kafka consumer lag per partition

### Logging
Structured logs are required for:
- state transitions,
- snapshot retries,
- bridging checks,
- gap detection,
- publish summary.

Log concrete sequencing values:
- `U`
- `u`
- `localUpdateId`
- snapshot update id

Without those numbers, incidents are difficult to debug.

---

## 19. Testing strategy

### Unit tests
- insert new level,
- update existing level,
- remove level on zero quantity,
- ignore duplicate event,
- gap -> resync.

### Bootstrap tests
- snapshot too old,
- bridging event exists,
- bridging event missing,
- stale buffered events discarded.

### Replay tests
Use recorded canonical diffs plus snapshots.
Verify:
- deterministic final book,
- deterministic top-N output,
- stable behavior across reruns.

### Invariant tests
- `localUpdateId` monotonic,
- `bestBid <= bestAsk`,
- no zero-quantity levels,
- same input -> same output.

---

## 20. Concrete implementation scope

The first complete version of the service should include:
1. canonical input contract,
2. snapshot port and Binance adapter,
3. `OrderBook` model,
4. `OrderBookApplier`,
5. `BinanceSpotSyncPolicy`,
6. per-symbol `SymbolState`,
7. `SymbolStateMachine`,
8. `ResyncCoordinator`,
9. `TopNProjector`,
10. `BboProjector`,
11. Kafka consumer,
12. Kafka publishers,
13. in-memory state store,
14. metrics and structured logging,
15. replay and invariant tests.

---

## 21. Implementation roadmap

### Stage 1. Contracts
Define:
- `DepthDiffEvent`
- `OrderBookTopNStateEvent`
- `BboStateEvent`

### Stage 2. Core model
Implement:
- `OrderBook`
- `SymbolState`
- `SyncStatus`
- `SyncDecision`
- `ResyncReason`

### Stage 3. Core logic
Implement:
- `OrderBookApplier`
- `BinanceSpotSyncPolicy`
- `TopNProjector`
- `BboProjector`

### Stage 4. State machine
Implement:
- `SymbolStateMachine`
- `ResyncCoordinator`

### Stage 5. Adapters
Implement:
- `BinanceSpotSnapshotAdapter`
- Kafka consumer
- Kafka publishers
- `InMemorySymbolStateStore`

### Stage 6. Observability
Add:
- metrics,
- logs,
- alert-ready fields.

### Stage 7. Tests
Add:
- unit tests,
- bootstrap tests,
- gap/resync tests,
- replay tests,
- invariant tests.

### Stage 8. Hardening
Validate:
- lag behavior,
- throughput,
- restart behavior,
- bounded retries and backoff.

---

## 22. Definition of Done

The service can be considered ready when:
- bootstrap follows Binance Spot rules correctly,
- `u < localUpdateId` is ignored correctly,
- `U > localUpdateId + 1` triggers resync,
- top-N and BBO are published with `trusted/status`,
- `localUpdateId` is monotonic,
- `bestBid <= bestAsk`,
- lag/gap/resync/state age metrics exist,
- deterministic replay tests exist.

---

## 23. Short summary

Market State Service is a service that, for each symbol:
- reconstructs a local L2 order book from snapshot + depth diff,
- guarantees sequence correctness,
- recovers from gaps and inconsistencies,
- publishes trusted top-N and BBO state as a standalone product for downstream systems.
