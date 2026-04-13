package com.trading.mss.service;

import com.trading.mss.domain.model.BufferedDepthDiff;
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
                    state.getSymbol(), maxBufferedEvents, state.getStatus(), state.isBootstrapInProgress(),
                    ctx.partition(), ctx.offset(), ctx.key());
            lifecycleService.enterResyncing(state, "buffer_overflow", ctx);
            return false;
        }

        state.bufferEvent(new BufferedDepthDiff(event, ctx));
        if (state.getFirstBufferedUpdateId() == null) {
            state.setFirstBufferedUpdateId(event.firstUpdateId());
        }
        log.info("BUFFERING: symbol={} U={} u={} bufferSize={} firstBufferedUpdateId={} status={} bootstrapInProgress={}",
                state.getSymbol(), event.firstUpdateId(), event.finalUpdateId(),
                state.getBufferedEvents().size(), state.getFirstBufferedUpdateId(),
                state.getStatus(), state.isBootstrapInProgress());
        return true;
    }
}
