package com.trading.mss.domain.model;

import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.dto.KafkaMessageContext;

public record BufferedDepthDiff(
        DepthDiffDto event,
        KafkaMessageContext context
) {}
