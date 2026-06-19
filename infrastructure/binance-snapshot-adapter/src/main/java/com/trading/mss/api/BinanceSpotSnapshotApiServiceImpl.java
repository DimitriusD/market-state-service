package com.trading.mss.api;

import com.trading.mss.domain.model.OrderBookSnapshot;
import com.trading.mss.dto.BinanceDepthLevel;
import com.trading.mss.dto.BinanceDepthResponse;
import com.trading.mss.dto.common.PriceLevelDto;
import com.trading.mss.port.output.BinanceSpotSnapshotApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class BinanceSpotSnapshotApiServiceImpl implements BinanceSpotSnapshotApiService {

    public static final String BINANCE = "binance";
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_BINANCE_IP_BANNED = 418;

    private final RestClient restClient;

    @Override
    public OrderBookSnapshot load(String symbol, int depthLimit) {
        log.info("Loading snapshot: symbol={} depthLimit={}", symbol, depthLimit);

        BinanceDepthResponse response = restClient.get()
                .uri("/api/v3/depth?symbol={symbol}&limit={limit}", symbol, depthLimit)
                .retrieve()
                .onStatus(status -> status.value() == HTTP_TOO_MANY_REQUESTS
                                || status.value() == HTTP_BINANCE_IP_BANNED,
                        (request, clientResponse) -> {
                            throw new BinanceRateLimitedException(
                                    clientResponse.getStatusCode().value(),
                                    parseRetryAfter(clientResponse.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
                        })
                .body(BinanceDepthResponse.class);

        if (response == null) {
            throw new IllegalStateException("Null response from Binance for symbol=" + symbol);
        }

        log.info("Snapshot loaded: symbol={} lastUpdateId={} bids={} asks={}",
                symbol, response.lastUpdateId(),
                response.bids() != null ? response.bids().size() : 0,
                response.asks() != null ? response.asks().size() : 0);

        return new OrderBookSnapshot(
                symbol,
                BINANCE,
                response.lastUpdateId(),
                toPriceLevels(response.bids()),
                toPriceLevels(response.asks()),
                depthLimit,
                System.currentTimeMillis()
        );

    }

    private static Duration parseRetryAfter(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(headerValue.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private List<PriceLevelDto> toPriceLevels(List<BinanceDepthLevel> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .map(level -> new PriceLevelDto(level.price(), level.qty()))
                .toList();
    }
}
