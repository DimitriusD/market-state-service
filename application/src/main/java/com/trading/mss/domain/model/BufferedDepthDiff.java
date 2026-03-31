package com.trading.mss.domain.model;

import com.trading.mss.message.inbound.DepthDiffEvent;
import com.trading.mss.message.inbound.KafkaMessageContext;

public record BufferedDepthDiff(
        DepthDiffEvent event,
        KafkaMessageContext context
) {}
