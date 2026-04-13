package com.trading.mss.dto.market;

import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.common.PriceLevelDto;

import java.util.List;

public record DepthDiffDto(
        MetadataDto metadataDto,
        Long transactionTs,
        long firstUpdateId,
        long finalUpdateId,
        Long previousFinalUpdateId,
        List<PriceLevelDto> bids,
        List<PriceLevelDto> asks
) {}
