package com.trading.mss.api;

import com.trading.mss.domain.model.OrderBookSnapshot;
import com.trading.mss.dto.BinanceDepthLevel;
import com.trading.mss.dto.BinanceDepthResponse;
import com.trading.mss.message.inbound.PriceLevel;
import com.trading.mss.port.output.BinanceSpotSnapshotApiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class BinanceSpotSnapshotApiServiceImpl implements BinanceSpotSnapshotApiService {

    public static final String BINANCE = "binance";
    private final RestClient restClient;

    @Override
    public OrderBookSnapshot load(String symbol, int depthLimit) {
        log.info("Loading snapshot: symbol={} depthLimit={}", symbol, depthLimit);

        BinanceDepthResponse response = restClient.get()
                .uri("/api/v3/depth?symbol={symbol}&limit={limit}", symbol, depthLimit)
                .retrieve()
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

    private List<PriceLevel> toPriceLevels(List<BinanceDepthLevel> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .map(level -> new PriceLevel(level.price(), level.qty()))
                .toList();
    }
}
