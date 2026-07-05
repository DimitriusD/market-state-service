package com.trading.mss.domain.model;

import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.dto.market.DepthDiffDto;
import lombok.Getter;

/**
 * Last consumed input position: Kafka coordinates of the last processed record plus the update ids
 * of the last input depth diff applied to the book. Written only through {@link #record}; used to
 * build source/status projections when no triggering event is at hand (snapshot callbacks, ticks).
 *
 * <p>The {@code ...Or} helpers implement the shared mapper fallback: prefer the triggering
 * event/context when present, otherwise fall back to the last recorded position.
 */
@Getter
public class InputPosition {

    private String topic;
    private String key;
    private int partition = -1;
    private long offset = -1;
    private Long firstUpdateId;
    private Long finalUpdateId;

    public void record(DepthDiffDto event, KafkaMessageContext ctx) {
        if (ctx != null) {
            this.topic = ctx.topic();
            this.key = ctx.key();
            this.partition = ctx.partition();
            this.offset = ctx.offset();
        }
        if (event != null) {
            this.firstUpdateId = event.firstUpdateId();
            this.finalUpdateId = event.finalUpdateId();
        }
    }

    public String topicOr(KafkaMessageContext ctx) {
        return ctx != null ? ctx.topic() : topic;
    }

    public String keyOr(KafkaMessageContext ctx) {
        return ctx != null ? ctx.key() : key;
    }

    public int partitionOr(KafkaMessageContext ctx) {
        return ctx != null ? ctx.partition() : partition;
    }

    public long offsetOr(KafkaMessageContext ctx) {
        return ctx != null ? ctx.offset() : offset;
    }

    public Long firstUpdateIdOr(DepthDiffDto event) {
        // Long.valueOf keeps the ternary boxed: a bare event.firstUpdateId() would force the
        // null fallback through a long-unboxing NPE.
        return event != null ? Long.valueOf(event.firstUpdateId()) : firstUpdateId;
    }

    public Long finalUpdateIdOr(DepthDiffDto event) {
        return event != null ? Long.valueOf(event.finalUpdateId()) : finalUpdateId;
    }
}
