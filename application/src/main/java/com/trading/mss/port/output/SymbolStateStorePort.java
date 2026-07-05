package com.trading.mss.port.output;

import com.trading.mss.domain.model.InstrumentKey;
import com.trading.mss.domain.model.SymbolState;

import java.util.Collection;

public interface SymbolStateStorePort {

    SymbolState loadOrCreate(InstrumentKey key);

    void save(SymbolState state);

    Collection<InstrumentKey> keys();
}
