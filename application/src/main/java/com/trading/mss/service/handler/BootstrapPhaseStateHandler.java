package com.trading.mss.service.handler;

import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.port.output.SymbolStateStorePort;
import com.trading.mss.service.DepthDiffBufferService;
import lombok.RequiredArgsConstructor;

/**
 * While a snapshot fetch is in flight ({@code SNAPSHOT_LOADING}), incoming diffs are only buffered:
 * the snapshot result arrives as its own serialized command ({@code onSnapshotReady}), no polling.
 * Also mapped to {@code APPLYING_BUFFER} defensively — that status never survives between commands
 * (snapshot apply runs through to LIVE/RESYNCING within one command).
 */
@RequiredArgsConstructor
public class BootstrapPhaseStateHandler implements DepthDiffStateHandler {

    private final DepthDiffBufferService bufferService;
    private final SymbolStateStorePort stateStore;

    @Override
    public SymbolStateStatus supportedStatus() {
        return SymbolStateStatus.SNAPSHOT_LOADING;
    }

    @Override
    public void handle(DepthDiffDto event, SymbolState state, KafkaMessageContext context) {
        if (!bufferService.bufferEvent(state, event, context)) {
            return;
        }
        stateStore.save(state);
    }
}
