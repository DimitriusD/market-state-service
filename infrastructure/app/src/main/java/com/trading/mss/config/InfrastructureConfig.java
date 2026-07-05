package com.trading.mss.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trading.mss.api.BinanceResilienceConfig;
import com.trading.mss.api.BinanceSpotSnapshotApiServiceImpl;
import com.trading.mss.api.ExecutorSnapshotFetcher;
import com.trading.mss.api.ResilientBinanceSnapshotApiService;
import com.trading.mss.dispatch.StripedSerialExecutor;
import com.trading.mss.mapper.OrderBookL2SnapshotMapper;
import com.trading.mss.mapper.OrderBookStatusMapper;
import com.trading.mss.port.input.DepthDiffProcessor;
import com.trading.mss.port.output.AsyncSnapshotPort;
import com.trading.mss.port.output.BinanceSpotSnapshotApiService;
import com.trading.mss.port.output.PublishOrderBookL2SnapshotPort;
import com.trading.mss.port.output.PublishOrderBookStatusPort;
import com.trading.mss.port.output.SymbolStateStorePort;
import com.trading.mss.service.*;
import com.trading.mss.service.handler.*;
import com.trading.mss.store.InMemorySymbolStateStore;
import com.trading.mss.watchdog.SymbolStateWatchdog;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(AppProperties.class)
public class InfrastructureConfig {

    private static final Logger log = LoggerFactory.getLogger(InfrastructureConfig.class);

    private final AppProperties props;

    public InfrastructureConfig(AppProperties props) {
        this.props = props;
    }

    @PostConstruct
    void validateConfig() {
        // A too-low timeout double-fires against a slow-but-alive fetch: the epoch guard keeps that
        // correct, but it wastes rate-limit budget and emits spurious SNAPSHOT_LOAD_FAILED statuses.
        if (props.snapshotTimeoutBelowFetchWorstCase()) {
            log.warn("app.state.snapshot-timeout-ms={} is below the fetch worst case ~{}ms "
                            + "(maxRetries x (connect + read + 1.5 x maxBackoff)) — expect spurious snapshot timeouts",
                    props.state().snapshotTimeoutMs(), props.snapshotFetchWorstCaseMs());
        }
    }

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

    /**
     * Serialization point for all symbol-state mutations. Registered as a bean so its
     * SmartLifecycle stop (phase 0) runs AFTER the Kafka listener containers stop.
     * stripes=1 is a deterministic global event loop; raise to 4-8 for 100+ active symbols
     * (one slow symbol otherwise delays all others).
     */
    @Bean
    public StripedSerialExecutor symbolExecutor() {
        AppProperties.State.Dispatcher dispatcher = props.state().dispatcher();
        return new StripedSerialExecutor(dispatcher.stripes(), dispatcher.queueCapacity());
    }

    @Bean
    public OrderBookApplier orderBookApplier() {
        return new OrderBookApplier();
    }

    @Bean
    public OrderBookStateApplier orderBookStateApplier(OrderBookApplier orderBookApplier) {
        return new OrderBookStateApplier(orderBookApplier);
    }

    @Bean
    public BinanceSpotSyncPolicy binanceSpotSyncPolicy() {
        return new BinanceSpotSyncPolicy();
    }

    @Bean
    public OrderBookL2SnapshotMapper orderBookL2SnapshotMapper(Clock clock) {
        return new OrderBookL2SnapshotMapper(clock);
    }

    @Bean
    public OrderBookStatusMapper orderBookStatusMapper(Clock clock) {
        return new OrderBookStatusMapper(clock);
    }

    @Bean
    public RestClient binanceRestClient() {
        AppProperties.Binance.Rest rest = props.binance().rest();
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofMillis(rest.connectTimeoutMs()))
                .withReadTimeout(Duration.ofMillis(rest.readTimeoutMs()));
        return RestClient.builder()
                .baseUrl(rest.baseUrl())
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .build();
    }

    @Bean
    public BinanceSpotSnapshotApiService snapshotPort(RestClient binanceRestClient, Clock clock) {
        AppProperties.Binance.Rest.Resilience resilience = props.binance().rest().resilience();
        BinanceSpotSnapshotApiService httpClient = new BinanceSpotSnapshotApiServiceImpl(binanceRestClient);
        BinanceResilienceConfig resilienceConfig = new BinanceResilienceConfig(
                resilience.rateLimitPerMinute(),
                Duration.ofMinutes(1),
                Duration.ofMillis(resilience.rateLimitTimeoutMs()),
                resilience.circuitFailureRateThreshold(),
                resilience.circuitSlidingWindowSize(),
                resilience.circuitMinimumCalls(),
                Duration.ofMillis(resilience.circuitWaitDurationMs()),
                resilience.circuitPermittedCallsHalfOpen());
        return new ResilientBinanceSnapshotApiService(httpClient, clock, resilienceConfig);
    }

    @Bean
    public ExecutorService snapshotFetchExecutor() {
        int poolSize = props.bootstrap().snapshotFetchPoolSize();
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
            ExecutorService snapshotFetchExecutor) {
        AppProperties.Bootstrap bootstrap = props.bootstrap();
        return new ExecutorSnapshotFetcher(
                snapshotPort,
                snapshotFetchExecutor,
                bootstrap.snapshotFetchMaxRetries(),
                bootstrap.snapshotFetchBaseBackoffMs(),
                bootstrap.snapshotFetchMaxBackoffMs());
    }

    @Bean
    public SymbolStateLifecycleService symbolStateLifecycleService(
            SymbolStateStorePort symbolStateStore,
            OrderBookStatusMapper orderBookStatusMapper,
            PublishOrderBookStatusPort publishOrderBookStatusPort,
            Clock clock) {
        return new SymbolStateLifecycleService(
                symbolStateStore, orderBookStatusMapper, publishOrderBookStatusPort, clock);
    }

    @Bean
    public MarketStatePublisher marketStatePublisher(
            OrderBookL2SnapshotMapper orderBookL2SnapshotMapper,
            PublishOrderBookL2SnapshotPort publishOrderBookL2SnapshotPort) {
        return new MarketStatePublisher(
                orderBookL2SnapshotMapper,
                publishOrderBookL2SnapshotPort,
                props.marketState().publish().topnDepth(),
                props.binance().snapshot().depthLimit());
    }

    @Bean
    public LiveOrderBookUpdateService liveOrderBookUpdateService(
            OrderBookStateApplier orderBookStateApplier,
            BinanceSpotSyncPolicy syncPolicy,
            SymbolStateStorePort symbolStateStore,
            SymbolStateLifecycleService symbolStateLifecycleService,
            MarketStatePublisher marketStatePublisher,
            Clock clock) {
        return new LiveOrderBookUpdateService(
                orderBookStateApplier,
                syncPolicy,
                symbolStateStore,
                symbolStateLifecycleService,
                marketStatePublisher,
                clock);
    }

    @Bean
    public DepthDiffBootstrapService depthDiffBootstrapService(
            SymbolStateStorePort symbolStateStore,
            OrderBookApplier orderBookApplier,
            OrderBookStateApplier orderBookStateApplier,
            BinanceSpotSyncPolicy syncPolicy,
            AsyncSnapshotPort asyncSnapshotPort,
            SymbolStateLifecycleService symbolStateLifecycleService,
            MarketStatePublisher marketStatePublisher,
            StripedSerialExecutor symbolExecutor,
            Clock clock) {
        return new DepthDiffBootstrapService(
                orderBookApplier,
                orderBookStateApplier,
                syncPolicy,
                asyncSnapshotPort,
                symbolStateStore,
                symbolStateLifecycleService,
                marketStatePublisher,
                symbolExecutor,
                props.binance().snapshot().depthLimit(),
                clock,
                props.state().bootstrapCooldownMs());
    }

    @Bean
    public DepthDiffBufferService depthDiffBufferService(
            SymbolStateLifecycleService symbolStateLifecycleService) {
        return new DepthDiffBufferService(symbolStateLifecycleService, props.state().maxBufferedEvents());
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
    public DepthDiffProcessor processDepthDiff(
            SymbolStateStorePort symbolStateStore,
            DepthDiffStateHandlerRegistry depthDiffStateHandlerRegistry) {
        return new DepthDiffService(symbolStateStore, depthDiffStateHandlerRegistry);
    }

    @Bean
    public SymbolTickService symbolTickService(
            SymbolStateStorePort symbolStateStore,
            DepthDiffBootstrapService depthDiffBootstrapService,
            SymbolStateLifecycleService symbolStateLifecycleService,
            Clock clock) {
        AppProperties.State state = props.state();
        return new SymbolTickService(
                symbolStateStore,
                depthDiffBootstrapService,
                symbolStateLifecycleService,
                clock,
                state.bootstrapCooldownMs(),
                state.snapshotTimeoutMs(),
                state.staleness().softThresholdMs(),
                state.staleness().hardThresholdMs());
    }

    @Bean
    public SymbolStateWatchdog symbolStateWatchdog(
            SymbolStateStorePort symbolStateStore,
            StripedSerialExecutor symbolExecutor,
            SymbolTickService symbolTickService) {
        return new SymbolStateWatchdog(symbolStateStore, symbolExecutor, symbolTickService);
    }
}
