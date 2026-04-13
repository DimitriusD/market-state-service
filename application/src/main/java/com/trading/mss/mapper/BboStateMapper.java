package com.trading.mss.mapper;

import com.trading.mss.domain.model.OrderBook;
import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.dto.common.MetadataDto;
import com.trading.mss.dto.common.PriceLevelDto;
import com.trading.mss.dto.orderbook.BboStateDto;
import com.trading.mss.dto.orderbook.BookSyncStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

public class BboStateMapper {

    private static final int SCHEMA_VERSION = 1;
    private static final String EVENT_TYPE = "bbo_state";

    public Optional<BboStateDto> project(SymbolState state) {
        OrderBook book = state.getOrderBook();

        if (!book.hasBids() || !book.hasAsks()) {
            return Optional.empty();
        }

        Long bestBidPrice = book.bestBid();
        Long bestAskPrice = book.bestAsk();

        if (bestBidPrice > bestAskPrice) {
            throw new IllegalStateException(
                    "Crossed order book: bestBid=%s > bestAsk=%s for symbol=%s".formatted(
                            ScaledDecimal.format(bestBidPrice),
                            ScaledDecimal.format(bestAskPrice),
                            state.getSymbol()));
        }

        PriceLevelDto bestBid = new PriceLevelDto(
                ScaledDecimal.format(bestBidPrice),
                ScaledDecimal.format(book.getBids().get(bestBidPrice)));

        PriceLevelDto bestAsk = new PriceLevelDto(
                ScaledDecimal.format(bestAskPrice),
                ScaledDecimal.format(book.getAsks().get(bestAskPrice)));

        String spread = ScaledDecimal.format(bestAskPrice - bestBidPrice);

        String mid = BigDecimal.valueOf(bestBidPrice, ScaledDecimal.SCALE_DIGITS)
                .add(BigDecimal.valueOf(bestAskPrice, ScaledDecimal.SCALE_DIGITS))
                .divide(BigDecimal.valueOf(2), ScaledDecimal.SCALE_DIGITS, RoundingMode.HALF_UP)
                .toPlainString();

        MetadataDto metadata = StateEventMetadataFactory.from(state, SCHEMA_VERSION, EVENT_TYPE);

        BookSyncStatus syncStatus =
                state.isTrusted() ? BookSyncStatus.IN_SYNC : BookSyncStatus.OUT_OF_SYNC;
        return Optional.of(new BboStateDto(metadata, bestBid, bestAsk, spread, mid, syncStatus));
    }
}
