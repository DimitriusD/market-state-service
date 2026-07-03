package com.trading.mss.domain.model;

import com.trading.mss.dto.common.MetadataDto;

/**
 * Identity of a per-instrument order book state.
 *
 * <p>{@code instrumentId} (canonical platform id, e.g. {@code BINANCE|SPOT|BTC|USDT}) is the
 * PRIMARY MSS identity: {@link #canonical()} is used for state-store map keys and dispatcher
 * stripe hashing, and it is also the Kafka key of every published snapshot/status event.
 *
 * <p>{@code exchange}/{@code marketType}/{@code symbol} are routing attributes, not identity:
 * the native exchange symbol (e.g. {@code BTCUSDT}) drives the Binance REST snapshot API and
 * sync-policy selection, and all three appear in metadata/logs. They never participate in map
 * keys or stripe hashing. There is no fallback identity mode — an event without a non-blank
 * {@code instrumentId} is invalid and must be skipped upstream, never keyed by symbol.
 *
 * <p>Values are trimmed but otherwise passed through verbatim: MSS neither reconstructs nor
 * reformats the canonical id — it comes from the upstream canonical event.
 */
public record InstrumentKey(
        String instrumentId,
        String exchange,
        String marketType,
        String symbol
) {
    public InstrumentKey {
        instrumentId = requireNonBlank(instrumentId, "instrumentId");
        exchange = requireNonBlank(exchange, "exchange");
        marketType = requireNonBlank(marketType, "marketType");
        symbol = requireNonBlank(symbol, "symbol");
    }

    public static InstrumentKey of(MetadataDto metadata) {
        if (metadata == null) {
            throw new IllegalArgumentException("metadata must not be null");
        }
        return new InstrumentKey(
                metadata.instrumentId(),
                metadata.exchange(),
                metadata.marketType(),
                metadata.symbol()
        );
    }

    public static InstrumentKey of(SymbolState state) {
        return new InstrumentKey(
                state.getInstrumentId(),
                state.getVenue(),
                state.getMarketType(),
                state.getSymbol()
        );
    }

    /** The primary identity: state-store map key and dispatcher stripe hash. */
    public String canonical() {
        return instrumentId;
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
