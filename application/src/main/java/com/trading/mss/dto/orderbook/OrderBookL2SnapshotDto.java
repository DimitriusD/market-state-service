package com.trading.mss.dto.orderbook;

import com.trading.mss.dto.common.MetadataDto;

public record OrderBookL2SnapshotDto(
        MetadataDto metadata,
        OrderBookVersionDto version,
        OrderBookQualityDto quality,
        BboProjectionDto bbo,
        OrderBookDepthProjectionDto depth,
        OrderBookSourceDto source
) {}
