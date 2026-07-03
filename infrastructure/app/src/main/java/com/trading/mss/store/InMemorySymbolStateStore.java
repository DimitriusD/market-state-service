package com.trading.mss.store;

import com.trading.mss.domain.model.InstrumentKey;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.port.output.SymbolStateStorePort;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class InMemorySymbolStateStore implements SymbolStateStorePort {

    private final ConcurrentMap<String, SymbolState> states = new ConcurrentHashMap<>();

    @Override
    public SymbolState loadOrCreate(InstrumentKey key) {
        return states.computeIfAbsent(key.canonical(), k -> new SymbolState(key));
    }

    @Override
    public void save(SymbolState state) {
        states.put(state.key().canonical(), state);
    }

    @Override
    public Collection<InstrumentKey> keys() {
        return states.values().stream().map(SymbolState::key).toList();
    }
}
