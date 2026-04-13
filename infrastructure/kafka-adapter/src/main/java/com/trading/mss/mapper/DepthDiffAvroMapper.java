package com.trading.mss.mapper;

import com.trading.contracts.common.EventMetadata;
import com.trading.contracts.market.DepthDiffEvent;
import com.trading.contracts.market.PriceLevel;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.common.PriceLevelDto;

import java.util.ArrayList;
import java.util.List;

public final class DepthDiffAvroMapper {

    private DepthDiffAvroMapper() {}

    public static DepthDiffDto toDto(DepthDiffEvent avro) {
        EventMetadata m = avro.getMetadata();
        MetadataDto metadata = new MetadataDto(
                m.getSchemaVersion(),
                m.getEventType(),
                m.getExchange(),
                m.getMarketType(),
                m.getBase(),
                m.getQuote(),
                m.getSymbol(),
                m.getInstrumentId(),
                m.getEventId(),
                m.getSourceStream(),
                m.getExchangeTs(),
                m.getReceivedTs(),
                m.getProcessedTs());

        return new DepthDiffDto(
                metadata,
                avro.getTransactionTs(),
                avro.getFirstUpdateId(),
                avro.getFinalUpdateId(),
                avro.getPreviousFinalUpdateId(),
                mapPriceLevels(avro.getBids()),
                mapPriceLevels(avro.getAsks()));
    }

    private static List<PriceLevelDto> mapPriceLevels(List<PriceLevel> levels) {
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }
        List<PriceLevelDto> out = new ArrayList<>(levels.size());
        for (PriceLevel pl : levels) {
            out.add(new PriceLevelDto(pl.getPrice(), pl.getQty()));
        }
        return out;
    }
}
