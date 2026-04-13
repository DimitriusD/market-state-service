package com.trading.mss.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trading.mss.api.BinanceSpotSnapshotApiServiceImpl;
import com.trading.mss.mapper.BboStateMapper;
import com.trading.mss.mapper.OrderBookDepthStateMapper;
import com.trading.mss.port.input.ProcessDepthDiffUseCase;
import com.trading.mss.port.output.BinanceSpotSnapshotApiService;
import com.trading.mss.port.output.PublishBboStatePort;
import com.trading.mss.port.output.PublishOrderBookDepthStatePort;
import com.trading.mss.port.output.SymbolStateStorePort;
import com.trading.mss.service.*;
import com.trading.mss.service.handler.*;
import com.trading.mss.store.InMemorySymbolStateStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class InfrastructureConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    @Bean
    public SymbolStateStorePort symbolStateStore() {
        return new InMemorySymbolStateStore();
    }

    @Bean
    public OrderBookApplier orderBookApplier() {
        return new OrderBookApplier();
    }

    @Bean
    public BinanceSpotSyncPolicy binanceSpotSyncPolicy() {
        return new BinanceSpotSyncPolicy();
    }

    @Bean
    public BboStateMapper bboStateMapper() {
        return new BboStateMapper();
    }

    @Bean
    public OrderBookDepthStateMapper orderBookDepthStateMapper() {
        return new OrderBookDepthStateMapper();
    }

    @Bean
    public RestClient binanceRestClient(
            @Value("${app.binance.rest.base-url:https://api.binance.com}") String baseUrl) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    @Bean
    public BinanceSpotSnapshotApiService snapshotPort(RestClient binanceRestClient) {
        return new BinanceSpotSnapshotApiServiceImpl(binanceRestClient);
    }

    @Bean
    public SymbolStateLifecycleService symbolStateLifecycleService(SymbolStateStorePort symbolStateStore) {
        return new SymbolStateLifecycleService(symbolStateStore);
    }

    @Bean
    public MarketStatePublisher marketStatePublisher(
            BboStateMapper bboStateMapper,
            OrderBookDepthStateMapper orderBookDepthStateMapper,
            PublishBboStatePort publishBboStatePort,
            PublishOrderBookDepthStatePort publishOrderBookDepthStatePort,
            @Value("${app.market-state.publish.topn-depth:10}") int publishedLevels) {
        return new MarketStatePublisher(
                bboStateMapper,
                orderBookDepthStateMapper,
                publishBboStatePort,
                publishOrderBookDepthStatePort,
                publishedLevels);
    }

    @Bean
    public LiveOrderBookUpdateService liveOrderBookUpdateService(
            OrderBookApplier orderBookApplier,
            BinanceSpotSyncPolicy syncPolicy,
            SymbolStateStorePort symbolStateStore,
            SymbolStateLifecycleService symbolStateLifecycleService,
            MarketStatePublisher marketStatePublisher) {
        return new LiveOrderBookUpdateService(
                orderBookApplier,
                syncPolicy,
                symbolStateStore,
                symbolStateLifecycleService,
                marketStatePublisher);
    }

    @Bean
    public DepthDiffBootstrapService depthDiffBootstrapService(
            SymbolStateStorePort symbolStateStore,
            OrderBookApplier orderBookApplier,
            BinanceSpotSyncPolicy syncPolicy,
            BinanceSpotSnapshotApiService snapshotPort,
            SymbolStateLifecycleService symbolStateLifecycleService,
            MarketStatePublisher marketStatePublisher,
            @Value("${app.binance.snapshot.depth-limit:1000}") int snapshotDepthLimit) {
        return new DepthDiffBootstrapService(
                orderBookApplier,
                syncPolicy,
                snapshotPort,
                symbolStateStore,
                symbolStateLifecycleService,
                marketStatePublisher,
                snapshotDepthLimit);
    }

    @Bean
    public DepthDiffBufferService depthDiffBufferService(
            SymbolStateLifecycleService symbolStateLifecycleService,
            @Value("${app.state.max-buffered-events:10000}") int maxBufferedEvents) {
        return new DepthDiffBufferService(symbolStateLifecycleService, maxBufferedEvents);
    }

    @Bean
    public DepthDiffStateHandlerRegistry depthDiffStateHandlerRegistry(
            DepthDiffBufferService depthDiffBufferService,
            DepthDiffBootstrapService depthDiffBootstrapService,
            LiveOrderBookUpdateService liveOrderBookUpdateService,
            SymbolStateLifecycleService symbolStateLifecycleService,
            SymbolStateStorePort symbolStateStore) {
        BootstrapPhaseStateHandler bootstrapPhaseHandler =
                new BootstrapPhaseStateHandler(depthDiffBufferService, symbolStateStore);

        DepthDiffStateHandlerRegistry registry = new DepthDiffStateHandlerRegistry(java.util.List.of(
                new InitDepthDiffStateHandler(depthDiffBufferService, depthDiffBootstrapService),
                new BufferingDiffsStateHandler(depthDiffBufferService, depthDiffBootstrapService),
                bootstrapPhaseHandler,
                new LiveDepthDiffStateHandler(liveOrderBookUpdateService),
                new ResyncingDepthDiffStateHandler(depthDiffBufferService, depthDiffBootstrapService, symbolStateLifecycleService)
        ));
        registry.registerAdditionalStatus(
                com.trading.mss.domain.model.SymbolStateStatus.APPLYING_BUFFER, bootstrapPhaseHandler);
        return registry;
    }

    @Bean
    public ProcessDepthDiffUseCase processDepthDiff(
            SymbolStateStorePort symbolStateStore,
            DepthDiffStateHandlerRegistry depthDiffStateHandlerRegistry) {
        return new ProcessDepthDiffService(symbolStateStore, depthDiffStateHandlerRegistry);
    }
}
