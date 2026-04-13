package com.trading.mss.service.handler;

import com.trading.mss.domain.model.SymbolStateStatus;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class DepthDiffStateHandlerRegistry {

    private final Map<SymbolStateStatus, DepthDiffStateHandler> handlers;

    public DepthDiffStateHandlerRegistry(List<DepthDiffStateHandler> handlerList) {
        this.handlers = new EnumMap<>(SymbolStateStatus.class);
        for (DepthDiffStateHandler handler : handlerList) {
            handlers.put(handler.supportedStatus(), handler);
        }
    }

    public void registerAdditionalStatus(SymbolStateStatus status, DepthDiffStateHandler handler) {
        handlers.put(status, handler);
    }

    public DepthDiffStateHandler getHandler(SymbolStateStatus status) {
        DepthDiffStateHandler handler = handlers.get(status);
        if (handler == null) {
            throw new IllegalStateException("No handler registered for status: " + status);
        }
        return handler;
    }
}
