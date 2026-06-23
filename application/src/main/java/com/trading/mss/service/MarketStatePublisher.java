package com.trading.mss.service;

import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.mapper.OrderBookL2SnapshotMapper;
import com.trading.mss.port.output.PublishOrderBookL2SnapshotPort;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MarketStatePublisher {

    private final OrderBookL2SnapshotMapper snapshotMapper;
    private final PublishOrderBookL2SnapshotPort snapshotPublisher;
    private final int publishedDepth;
    private final int snapshotDepthLimit;

    /**
     * Publishes exactly one {@code OrderBookL2SnapshotEvent} iff the state is LIVE, trusted and the
     * book is valid (not crossed, two-sided). Otherwise nothing is published — invalid/out-of-sync
     * books must be reported via an {@code OrderBookStatusEvent}, not as trusted market state.
     */
    public void publishSnapshotIfLive(SymbolState state, DepthDiffDto triggeringEvent, KafkaMessageContext ctx) {
        snapshotMapper.project(state, triggeringEvent, ctx, publishedDepth, snapshotDepthLimit)
                .ifPresent(snapshotPublisher::publish);
    }
}
