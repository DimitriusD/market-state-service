package com.trading.mss.mapper;

import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.dto.common.MetadataDto;

public final class StateEventMetadataFactory {

    private static final String SOURCE_STREAM = "mss";

    private StateEventMetadataFactory() {}

    public static MetadataDto from(SymbolState state, int schemaVersion, String eventType) {
        String eventId = state.getLocalUpdateId() >= 0 ? Long.toString(state.getLocalUpdateId()) : "";
        return new MetadataDto(
                schemaVersion,
                eventType,
                nullToEmpty(state.getVenue()),
                nullToEmpty(state.getMarketType()),
                nullToEmpty(state.getBase()),
                nullToEmpty(state.getQuote()),
                nullToEmpty(state.getSymbol()),
                nullToEmpty(state.getInstrumentId()),
                eventId,
                SOURCE_STREAM,
                state.getLastEventExchangeTs(),
                state.getLastEventReceivedTs(),
                state.getLastEventProcessedTs());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
