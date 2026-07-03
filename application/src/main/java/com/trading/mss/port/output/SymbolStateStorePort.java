package com.trading.mss.port.output;

import com.trading.mss.domain.model.SymbolKey;
import com.trading.mss.domain.model.SymbolState;

import java.util.Collection;

public interface SymbolStateStorePort {

    SymbolState loadOrCreate(SymbolKey key);

    void save(SymbolState state);

    /**
     * Immutable identities of all known symbols. Deliberately does NOT expose the states
     * themselves: {@code SymbolState} must only be touched from inside that symbol's serialized
     * commands (see {@link SymbolExecutorPort}); callers iterate keys and submit commands.
     */
    Collection<SymbolKey> keys();
}
