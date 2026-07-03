package com.trading.mss.port.output;

import com.trading.mss.domain.model.SymbolKey;

import java.util.concurrent.Executor;

/**
 * Serialized command execution per symbol.
 *
 * <p>All mutation and logic-reads of a {@code SymbolState} MUST happen inside commands submitted
 * through this port for that symbol's key. Commands for the same key run strictly sequentially in
 * submission order; commands for different keys may run concurrently.
 */
public interface SymbolExecutorPort {

    /**
     * Executor for the given symbol. {@code execute} blocks the caller when the underlying queue
     * is full (backpressure) — use from the Kafka listener and snapshot-fetch callbacks, never from
     * a symbol command of the same stripe's worker (the implementation must handle self-submission
     * without deadlock).
     */
    Executor executorFor(SymbolKey key);

    /**
     * Non-blocking submission for droppable commands (watchdog ticks).
     *
     * @return {@code false} if the command was rejected (queue full or dispatcher stopped)
     */
    boolean tryExecute(SymbolKey key, Runnable task);
}
