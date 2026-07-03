package com.trading.mss.domain.model;

import com.trading.mss.dto.common.MetadataDto;

/**
 * Identity of a per-symbol order book state and the routing key for serialized command execution.
 *
 * <p>{@code instrumentId} is deliberately NOT part of the identity: it arrives with event metadata
 * and may be blank when the state is first created, so it stays a plain field on the state.
 * Including {@code marketType} keeps SPOT and FUTURES books with the same symbol distinct.
 */
public record SymbolKey(String exchange, String marketType, String symbol) {

    public SymbolKey {
        exchange = nullToEmpty(exchange);
        marketType = nullToEmpty(marketType);
        symbol = nullToEmpty(symbol);
    }

    public static SymbolKey of(MetadataDto metadata) {
        return new SymbolKey(metadata.exchange(), metadata.marketType(), metadata.symbol());
    }

    public static SymbolKey of(SymbolState state) {
        return new SymbolKey(state.getVenue(), state.getMarketType(), state.getSymbol());
    }

    /** Canonical string form used as the state-store map key and for stripe hashing. */
    public String canonical() {
        return exchange + ":" + marketType + ":" + symbol;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
