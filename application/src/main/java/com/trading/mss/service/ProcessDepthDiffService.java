package com.trading.mss.service;

import com.trading.mss.message.inbound.DepthDiffEvent;
import com.trading.mss.message.inbound.KafkaMessageContext;
import com.trading.mss.port.input.ProcessDepthDiffUseCase;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessDepthDiffService implements ProcessDepthDiffUseCase {

    @Override
    public void process(DepthDiffEvent event, KafkaMessageContext context) {
        var m = event.metadata();

        log.info("Processing depth diff: symbol={} exchange={} marketType={} "
                        + "U={} u={} pu={} bids={} asks={} "
                        + "exchangeTs={} receivedTs={} processedTs={} "
                        + "partition={} offset={} key={}",
                m.symbol(), m.exchange(), m.marketType(),
                event.firstUpdateId(), event.finalUpdateId(), event.previousFinalUpdateId(),
                event.bids().size(), event.asks().size(),
                m.exchangeTs(), m.receivedTs(), m.processedTs(),
                context.partition(), context.offset(), context.key());
    }
}
