package com.trading.mss.watchdog;

import com.trading.mss.domain.model.SymbolKey;
import com.trading.mss.port.output.SymbolExecutorPort;
import com.trading.mss.port.output.SymbolStateStorePort;
import com.trading.mss.service.SymbolTickService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Submits a per-symbol tick command every interval. Never touches {@code SymbolState} itself —
 * it only iterates immutable keys and enqueues; all status inspection happens inside the command
 * on the symbol's stripe. Uses non-blocking {@code tryExecute}: ticks are idempotent 1 Hz probes,
 * dropping one on a full queue (or a stopped dispatcher) is harmless and must never block the
 * scheduler thread.
 */
@Slf4j
@RequiredArgsConstructor
public class SymbolStateWatchdog {

    private final SymbolStateStorePort stateStore;
    private final SymbolExecutorPort symbolExecutor;
    private final SymbolTickService tickService;

    private final AtomicLong droppedTicks = new AtomicLong();

    @Scheduled(fixedDelayString = "${app.state.watchdog.interval-ms:1000}")
    public void tick() {
        for (SymbolKey key : stateStore.keys()) {
            boolean accepted = symbolExecutor.tryExecute(key, () -> tickService.onTick(key));
            if (!accepted) {
                long total = droppedTicks.incrementAndGet();
                log.debug("Tick dropped (queue full or dispatcher stopped): key={} totalDropped={}",
                        key.canonical(), total);
            }
        }
    }

    public long droppedTickCount() {
        return droppedTicks.get();
    }
}
