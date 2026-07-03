package com.trading.mss.mapper;

import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.dto.common.MetadataDto;

import java.util.regex.Pattern;

/**
 * Builds the common {@link MetadataDto} envelope and event ids for published order book state events.
 */
public final class StateMetadataFactory {

    public static final int SCHEMA_VERSION = 1;
    public static final String SOURCE_STREAM = "market-state-service";

    private static final Pattern UNSAFE_ID_CHARS = Pattern.compile("[^A-Za-z0-9]+");

    private StateMetadataFactory() {}

    public static MetadataDto build(
            SymbolState state, String eventType, String eventId,
            long exchangeTs, long receivedTs, long processedTs) {
        return new MetadataDto(
                SCHEMA_VERSION,
                eventType,
                nullToEmpty(state.getVenue()),
                nullToEmpty(state.getMarketType()),
                nullToEmpty(state.getBase()),
                nullToEmpty(state.getQuote()),
                nullToEmpty(state.getSymbol()),
                nullToEmpty(state.getInstrumentId()),
                eventId,
                SOURCE_STREAM,
                exchangeTs,
                receivedTs,
                processedTs);
    }

    /** Instrument id safe for use inside an eventId: non-alphanumeric chars collapsed to '-'. */
    public static String safeInstrumentId(SymbolState state) {
        String raw = state.getInstrumentId();
        if (raw == null || raw.isBlank()) {
            raw = state.getSymbol();
        }
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return UNSAFE_ID_CHARS.matcher(raw).replaceAll("-");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
