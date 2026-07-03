package com.trading.mss.port.output;

import com.trading.mss.domain.model.InstrumentKey;
import com.trading.mss.domain.model.SymbolState;

import java.util.Collection;

public interface SymbolStateStorePort {

    SymbolState loadOrCreate(InstrumentKey key);

    void save(SymbolState state);

    /**
     * Immutable identities of all known symbols. Deliberately does NOT expose the states
     * themselves: {@code SymbolState} must only be touched from inside that symbol's serialized
     * commands (see {@link SymbolExecutorPort}); callers iterate keys and submit commands.
     */
    Collection<InstrumentKey> keys();
}
