package com.trading.mss.consumer;

import com.trading.contracts.common.MetadataEvent;
import com.trading.contracts.market.DepthDiffEvent;
import com.trading.mss.domain.model.InstrumentKey;
import com.trading.mss.dto.KafkaMessageContext;
import com.trading.mss.dto.market.DepthDiffDto;
import com.trading.mss.port.input.DepthDiffProcessor;
import com.trading.mss.port.output.SymbolExecutorPort;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class DepthDiffConsumerTest {

    private static final String TOPIC = "canonical.market.depthdiff.v1";
    private static final String INSTRUMENT_ID = "BINANCE|SPOT|BTC|USDT";

    private final RecordingProcessor processor = new RecordingProcessor();
    private final RecordingExecutor executor = new RecordingExecutor();
    private final DepthDiffConsumer consumer = new DepthDiffConsumer(processor, executor);

    @Test
    void blankInstrumentId_recordSkipped_processorNotCalled() {
        consumer.consume(record(event(metadata("", "binance", "spot", "BTCUSDT"))));

        assertTrue(processor.processed.isEmpty());
        assertTrue(executor.keys.isEmpty());
    }

    @Test
    void blankMarketType_recordSkipped() {
        consumer.consume(record(event(metadata(INSTRUMENT_ID, "binance", "", "BTCUSDT"))));

        assertTrue(processor.processed.isEmpty());
    }

    @Test
    void blankExchangeOrSymbol_recordSkipped() {
        consumer.consume(record(event(metadata(INSTRUMENT_ID, "", "spot", "BTCUSDT"))));
        consumer.consume(record(event(metadata(INSTRUMENT_ID, "binance", "spot", ""))));

        assertTrue(processor.processed.isEmpty());
    }

    @Test
    void nullValueOrMetadata_recordSkipped() {
        consumer.consume(record(null));

        assertTrue(processor.processed.isEmpty());
    }

    @Test
    void validEvent_dispatchedWithInstrumentIdAsCanonicalKey() {
        consumer.consume(record(event(metadata(INSTRUMENT_ID, "binance", "spot", "BTCUSDT"))));

        assertEquals(1, processor.processed.size());
        assertEquals(1, executor.keys.size());
        InstrumentKey key = executor.keys.getFirst();
        assertEquals(INSTRUMENT_ID, key.canonical());
        assertEquals("BTCUSDT", key.symbol());
        assertEquals(INSTRUMENT_ID, processor.processed.getFirst().metadataDto().instrumentId());
    }

    private static ConsumerRecord<String, DepthDiffEvent> record(DepthDiffEvent value) {
        return new ConsumerRecord<>(TOPIC, 0, 42L, INSTRUMENT_ID, value);
    }

    private static DepthDiffEvent event(MetadataEvent metadata) {
        return DepthDiffEvent.newBuilder()
                .setMetadata(metadata)
                .setTransactionTs(1_700_000_000_000L)
                .setFirstUpdateId(100)
                .setFinalUpdateId(105)
                .setPreviousFinalUpdateId(null)
                .setBids(List.of())
                .setAsks(List.of())
                .build();
    }

    private static MetadataEvent metadata(String instrumentId, String exchange, String marketType, String symbol) {
        return MetadataEvent.newBuilder()
                .setSchemaVersion(1)
                .setEventType("depthDiff")
                .setExchange(exchange)
                .setMarketType(marketType)
                .setBase("BTC")
                .setQuote("USDT")
                .setSymbol(symbol)
                .setInstrumentId(instrumentId)
                .setEventId("evt-1")
                .setSourceStream("stream-1")
                .setExchangeTs(1L)
                .setReceivedTs(2L)
                .setProcessedTs(3L)
                .build();
    }

    static class RecordingProcessor implements DepthDiffProcessor {
        final List<DepthDiffDto> processed = new ArrayList<>();

        @Override
        public void process(DepthDiffDto event, KafkaMessageContext context) {
            processed.add(event);
        }
    }

    static class RecordingExecutor implements SymbolExecutorPort {
        final List<InstrumentKey> keys = new ArrayList<>();

        @Override
        public Executor executorFor(InstrumentKey key) {
            keys.add(key);
            return Runnable::run;
        }

        @Override
        public boolean tryExecute(InstrumentKey key, Runnable task) {
            keys.add(key);
            task.run();
            return true;
        }
    }
}
