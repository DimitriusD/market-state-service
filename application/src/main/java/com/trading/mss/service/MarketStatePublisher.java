package com.trading.mss.service;

import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.mapper.BboStateMapper;
import com.trading.mss.mapper.OrderBookDepthStateMapper;
import com.trading.mss.port.output.PublishBboStatePort;
import com.trading.mss.port.output.PublishOrderBookDepthStatePort;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MarketStatePublisher {

    private final BboStateMapper bboStateMapper;
    private final OrderBookDepthStateMapper orderBookDepthStateMapper;
    private final PublishBboStatePort publishBboStatePort;
    private final PublishOrderBookDepthStatePort publishOrderBookDepthStatePort;
    private final int publishedLevels;

    public void publishProjectedStateIfLive(SymbolState state) {
        if (state.getStatus() != SymbolStateStatus.LIVE || !state.isTrusted()) {
            return;
        }

        bboStateMapper.project(state).ifPresent(publishBboStatePort::publish);
        publishOrderBookDepthStatePort.publish(orderBookDepthStateMapper.project(state, publishedLevels));
    }
}
