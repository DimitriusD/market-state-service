package com.trading.mss.service;

import com.trading.mss.domain.model.BufferedDepthDiff;
import com.trading.mss.domain.model.OrderBookReason;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class DepthDiffBufferService {

    private final SymbolStateLifecycleService lifecycleService;
    private final int maxBufferedEvents;

    public boolean bufferEvent(SymbolState state, DepthDiffDto event, KafkaMessageContext ctx) {
        if (state.getBufferedEvents().size() >= maxBufferedEvents) {
            log.warn("BUFFER_OVERFLOW: symbol={} maxBufferedEvents={} status={} bootstrapInProgress={} partition={} offset={} key={}",
                    state.getSymbol(), maxBufferedEvents, state.getStatus(), state.getBootstrap().isInProgress(),
                    ctx.partition(), ctx.offset(), ctx.key());
            lifecycleService.enterResyncing(state, OrderBookReason.BUFFER_OVERFLOW, ctx);
            return false;
        }

        state.bufferEvent(new BufferedDepthDiff(event, ctx));
        state.getBootstrap().noteFirstBuffered(event.firstUpdateId());
        log.info("BUFFERING: symbol={} U={} u={} bufferSize={} firstBufferedUpdateId={} status={} bootstrapInProgress={}",
                state.getSymbol(), event.firstUpdateId(), event.finalUpdateId(),
                state.getBufferedEvents().size(), state.getBootstrap().getFirstBufferedUpdateId(),
                state.getStatus(), state.getBootstrap().isInProgress());
        return true;
    }
}
