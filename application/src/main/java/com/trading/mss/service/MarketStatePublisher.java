package com.trading.mss.service;

import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.domain.model.SymbolStateStatus;
import com.trading.mss.mapper.BboStateMapper;
import com.trading.mss.mapper.OrderBookTopNStateMapper;
import com.trading.mss.port.output.PublishBboStatePort;
import com.trading.mss.port.output.PublishOrderBookTopNStatePort;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MarketStatePublisher {

    private final BboStateMapper bboStateMapper;
    private final OrderBookTopNStateMapper orderBookTopNStateMapper;
    private final PublishBboStatePort publishBboStatePort;
    private final PublishOrderBookTopNStatePort publishOrderBookTopNStatePort;
    private final int topNDepth;

    public void publishProjectedStateIfLive(SymbolState state) {
        if (state.getStatus() != SymbolStateStatus.LIVE || !state.isTrusted()) {
            return;
        }

        bboStateMapper.project(state).ifPresent(publishBboStatePort::publish);
        publishOrderBookTopNStatePort.publish(orderBookTopNStateMapper.project(state, topNDepth));
    }
}
