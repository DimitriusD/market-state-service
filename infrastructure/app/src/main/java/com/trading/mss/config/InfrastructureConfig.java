package com.trading.mss.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trading.mss.api.BinanceResilienceConfig;
import com.trading.mss.api.BinanceSpotSnapshotApiServiceImpl;
import com.trading.mss.api.ExecutorSnapshotFetcher;
import com.trading.mss.api.ResilientBinanceSnapshotApiService;
import com.trading.mss.mapper.BboStateMapper;
import com.trading.mss.mapper.OrderBookDepthStateMapper;
import com.trading.mss.port.input.ProcessDepthDiffUseCase;
import com.trading.mss.port.output.AsyncSnapshotPort;
import com.trading.mss.port.output.BinanceSpotSnapshotApiService;
import com.trading.mss.port.output.PublishBboStatePort;
import com.trading.mss.port.output.PublishOrderBookDepthStatePort;
import com.trading.mss.port.output.SymbolStateStorePort;
import com.trading.mss.service.*;
import com.trading.mss.service.handler.*;
import com.trading.mss.store.InMemorySymbolStateStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class InfrastructureConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

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
            @Value("${app.binance.rest.base-url:https://api.binance.com}") String baseUrl,
            @Value("${app.binance.rest.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${app.binance.rest.read-timeout-ms:5000}") long readTimeoutMs) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .withReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Bean
    public BinanceSpotSnapshotApiService snapshotPort(
            RestClient binanceRestClient,
            Clock clock,
            @Value("${app.binance.rest.resilience.rate-limit-per-minute:100}") int rateLimitPerMinute,
            @Value("${app.binance.rest.resilience.rate-limit-timeout-ms:0}") long rateLimitTimeoutMs,
            @Value("${app.binance.rest.resilience.circuit-failure-rate-threshold:50}") float circuitFailureRateThreshold,
            @Value("${app.binance.rest.resilience.circuit-sliding-window-size:10}") int circuitSlidingWindowSize,
            @Value("${app.binance.rest.resilience.circuit-minimum-calls:5}") int circuitMinimumCalls,
            @Value("${app.binance.rest.resilience.circuit-wait-duration-ms:30000}") long circuitWaitDurationMs,
            @Value("${app.binance.rest.resilience.circuit-permitted-calls-half-open:2}") int circuitPermittedCallsHalfOpen) {
        BinanceSpotSnapshotApiService httpClient = new BinanceSpotSnapshotApiServiceImpl(binanceRestClient);
        BinanceResilienceConfig resilienceConfig = new BinanceResilienceConfig(
                rateLimitPerMinute,
                Duration.ofMinutes(1),
                Duration.ofMillis(rateLimitTimeoutMs),
                circuitFailureRateThreshold,
                circuitSlidingWindowSize,
                circuitMinimumCalls,
                Duration.ofMillis(circuitWaitDurationMs),
                circuitPermittedCallsHalfOpen);
        return new ResilientBinanceSnapshotApiService(httpClient, clock, resilienceConfig);
    }

    /** Pool for off-consumer-thread snapshot fetches. Spring calls {@code shutdown()} on context close. */
    @Bean
    public ExecutorService snapshotFetchExecutor(
            @Value("${app.bootstrap.snapshot-fetch-pool-size:4}") int poolSize) {
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "snapshot-fetch-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        return Executors.newFixedThreadPool(poolSize, threadFactory);
    }

    @Bean
    public AsyncSnapshotPort asyncSnapshotPort(
            BinanceSpotSnapshotApiService snapshotPort,
            ExecutorService snapshotFetchExecutor,
            @Value("${app.bootstrap.snapshot-fetch-max-retries:3}") int maxRetries,
            @Value("${app.bootstrap.snapshot-fetch-base-backoff-ms:200}") long baseBackoffMs,
            @Value("${app.bootstrap.snapshot-fetch-max-backoff-ms:2000}") long maxBackoffMs) {
        return new ExecutorSnapshotFetcher(snapshotPort, snapshotFetchExecutor, maxRetries, baseBackoffMs, maxBackoffMs);
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
            AsyncSnapshotPort asyncSnapshotPort,
            SymbolStateLifecycleService symbolStateLifecycleService,
            MarketStatePublisher marketStatePublisher,
            Clock clock,
            @Value("${app.binance.snapshot.depth-limit:1000}") int snapshotDepthLimit,
            @Value("${app.state.bootstrap-cooldown-ms:5000}") long bootstrapCooldownMs) {
        return new DepthDiffBootstrapService(
                orderBookApplier,
                syncPolicy,
                asyncSnapshotPort,
                symbolStateStore,
                symbolStateLifecycleService,
                marketStatePublisher,
                snapshotDepthLimit,
                clock,
                bootstrapCooldownMs);
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
                new BootstrapPhaseStateHandler(depthDiffBufferService, depthDiffBootstrapService, symbolStateStore);

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
