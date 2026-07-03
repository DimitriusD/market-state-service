package com.trading.mss.domain.model;

import com.trading.mss.dto.common.MetadataDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InstrumentKeyTest {

    @Test
    void canonical_isInstrumentId() {
        InstrumentKey key = new InstrumentKey("BINANCE|SPOT|BTC|USDT", "binance", "spot", "BTCUSDT");
        assertEquals("BINANCE|SPOT|BTC|USDT", key.canonical());
    }

    @Test
    void blankInstrumentId_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new InstrumentKey(null, "binance", "spot", "BTCUSDT"));
        assertThrows(IllegalArgumentException.class,
                () -> new InstrumentKey("  ", "binance", "spot", "BTCUSDT"));
    }

    @Test
    void blankExchangeMarketTypeOrSymbol_isRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new InstrumentKey("BINANCE|SPOT|BTC|USDT", null, "spot", "BTCUSDT"));
        assertThrows(IllegalArgumentException.class,
                () -> new InstrumentKey("BINANCE|SPOT|BTC|USDT", "binance", "", "BTCUSDT"));
        assertThrows(IllegalArgumentException.class,
                () -> new InstrumentKey("BINANCE|SPOT|BTC|USDT", "binance", "spot", " "));
    }

    @Test
    void sameSymbolDifferentInstrumentIds_areDifferentKeys() {
        InstrumentKey spot = new InstrumentKey("BINANCE|SPOT|BTC|USDT", "binance", "spot", "BTCUSDT");
        InstrumentKey futures = new InstrumentKey("BINANCE|FUTURES|BTC|USDT", "binance", "futures", "BTCUSDT");

        assertNotEquals(spot.canonical(), futures.canonical());
        assertNotEquals(spot, futures);
    }

    @Test
    void valuesAreTrimmedButNotReformatted() {
        InstrumentKey key = new InstrumentKey(" BINANCE|SPOT|BTC|USDT ", " binance ", " spot ", " BTCUSDT ");

        assertEquals("BINANCE|SPOT|BTC|USDT", key.instrumentId());
        assertEquals("binance", key.exchange(), "no case normalization, trim only");
        assertEquals("spot", key.marketType());
        assertEquals("BTCUSDT", key.symbol());
    }

    @Test
    void ofMetadata_buildsKeyFromCanonicalEventMetadata() {
        var metadata = new MetadataDto(1, "depthDiff", "binance", "spot",
                "BTC", "USDT", "BTCUSDT", "BINANCE|SPOT|BTC|USDT", "evt-1", "stream-1", 1L, 2L, 3L);

        InstrumentKey key = InstrumentKey.of(metadata);

        assertEquals("BINANCE|SPOT|BTC|USDT", key.canonical());
        assertEquals("BTCUSDT", key.symbol());
    }

    @Test
    void ofNullMetadata_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> InstrumentKey.of((MetadataDto) null));
    }
}
