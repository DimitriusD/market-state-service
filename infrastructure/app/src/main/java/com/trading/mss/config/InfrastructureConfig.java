package com.trading.mss.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trading.mss.mapper.BboStateMapper;
import com.trading.mss.mapper.OrderBookTopNStateMapper;
import com.trading.mss.port.input.ProcessDepthDiffUseCase;
import com.trading.mss.port.output.BinanceSpotSnapshotApiService;
import com.trading.mss.port.output.PublishBboStatePort;
import com.trading.mss.port.output.PublishOrderBookTopNStatePort;
import com.trading.mss.port.output.SymbolStateStorePort;
import com.trading.mss.service.BinanceSpotSyncPolicy;
import com.trading.mss.service.DepthDiffBootstrapService;
import com.trading.mss.service.LiveOrderBookUpdateService;
import com.trading.mss.service.MarketStatePublisher;
import com.trading.mss.service.OrderBookApplier;
import com.trading.mss.service.ProcessDepthDiffService;
import com.trading.mss.service.SymbolStateLifecycleService;
import com.trading.mss.api.BinanceSpotSnapshotApiServiceImpl;
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
    public OrderBookTopNStateMapper orderBookTopNStateMapper() {
        return new OrderBookTopNStateMapper();
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
            OrderBookTopNStateMapper orderBookTopNStateMapper,
            PublishBboStatePort publishBboStatePort,
            PublishOrderBookTopNStatePort publishOrderBookTopNStatePort,
            @Value("${app.market-state.publish.topn-depth:10}") int topNDepth) {
        return new MarketStatePublisher(
                bboStateMapper,
                orderBookTopNStateMapper,
                publishBboStatePort,
                publishOrderBookTopNStatePort,
                topNDepth);
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
    public ProcessDepthDiffUseCase processDepthDiff(
            SymbolStateStorePort symbolStateStore,
            DepthDiffBootstrapService depthDiffBootstrapService,
            LiveOrderBookUpdateService liveOrderBookUpdateService,
            SymbolStateLifecycleService symbolStateLifecycleService,
            @Value("${app.state.max-buffered-events:10000}") int maxBufferedEvents) {
        return new ProcessDepthDiffService(
                symbolStateStore,
                depthDiffBootstrapService,
                liveOrderBookUpdateService,
                symbolStateLifecycleService,
                maxBufferedEvents);
    }
}
