package com.trading.mss.store;

import com.trading.mss.domain.model.InstrumentKey;
import com.trading.mss.domain.model.SymbolState;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;

class InMemorySymbolStateStoreTest {

    private static final InstrumentKey SPOT =
            new InstrumentKey("BINANCE|SPOT|BTC|USDT", "binance", "spot", "BTCUSDT");
    private static final InstrumentKey FUTURES =
            new InstrumentKey("BINANCE|FUTURES|BTC|USDT", "binance", "futures", "BTCUSDT");

    private final InMemorySymbolStateStore store = new InMemorySymbolStateStore();

    @Test
    void stateStoreKeysByInstrumentId_sameSymbolDifferentInstrumentsGetSeparateStates() {
        SymbolState spot = store.loadOrCreate(SPOT);
        SymbolState futures = store.loadOrCreate(FUTURES);

        assertNotSame(spot, futures, "same symbol must not collide across instrumentIds");
        assertSame(spot, store.loadOrCreate(SPOT), "same instrumentId must return the same state");
        assertEquals("BINANCE|SPOT|BTC|USDT", spot.getInstrumentId());
        assertEquals("BINANCE|FUTURES|BTC|USDT", futures.getInstrumentId());
    }

    @Test
    void keysReturnsInstrumentKeysNotMutableStates() {
        store.loadOrCreate(SPOT);
        store.loadOrCreate(FUTURES);

        Collection<InstrumentKey> keys = store.keys();

        assertEquals(2, keys.size());
        assertTrue(keys.contains(SPOT));
        assertTrue(keys.contains(FUTURES));
    }
}
