package com.trading.mss.store;

import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.port.output.SymbolStateStorePort;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemorySymbolStateStore implements SymbolStateStorePort {

    private final ConcurrentMap<String, SymbolState> states = new ConcurrentHashMap<>();

    @Override
    public SymbolState loadOrCreate(String symbol, String venue) {
        return states.computeIfAbsent(key(venue, symbol), k -> new SymbolState(symbol, venue));
    }

    @Override
    public void save(SymbolState state) {
        states.put(key(state.getVenue(), state.getSymbol()), state);
    }

    private static String key(String venue, String symbol) {
        return venue + ":" + symbol;
    }
}
