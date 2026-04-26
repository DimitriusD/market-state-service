package com.trading.mss.mapper;

import com.trading.contracts.common.MetadataEvent;
import com.trading.contracts.common.PriceLevelEvent;
import com.trading.contracts.market.DepthDiffEvent;
import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.common.PriceLevelDto;
import com.trading.mss.dto.market.DepthDiffDto;

import java.util.ArrayList;
import java.util.List;

public final class DepthDiffAvroMapper {

    private DepthDiffAvroMapper() {}

    public static DepthDiffDto toDto(DepthDiffEvent avro) {
        MetadataEvent metadataEvent = avro.getMetadata();
        MetadataDto metadata = new MetadataDto(
                metadataEvent.getSchemaVersion(),
                metadataEvent.getEventType(),
                metadataEvent.getExchange(),
                metadataEvent.getMarketType(),
                metadataEvent.getBase(),
                metadataEvent.getQuote(),
                metadataEvent.getSymbol(),
                metadataEvent.getInstrumentId(),
                metadataEvent.getEventId(),
                metadataEvent.getSourceStream(),
                metadataEvent.getExchangeTs(),
                metadataEvent.getReceivedTs(),
                metadataEvent.getProcessedTs());

        return new DepthDiffDto(
                metadata,
                avro.getTransactionTs(),
                avro.getFirstUpdateId(),
                avro.getFinalUpdateId(),
                avro.getPreviousFinalUpdateId(),
                mapPriceLevels(avro.getBids()),
                mapPriceLevels(avro.getAsks()));
    }

    private static List<PriceLevelDto> mapPriceLevels(List<PriceLevelEvent> levels) {
        if (levels == null || levels.isEmpty()) {
            return List.of();
        }
        List<PriceLevelDto> out = new ArrayList<>(levels.size());
        for (PriceLevelEvent pl : levels) {
            out.add(new PriceLevelDto(pl.getPrice(), pl.getQty()));
        }
        return out;
    }
}
