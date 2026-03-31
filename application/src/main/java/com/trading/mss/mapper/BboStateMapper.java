package com.trading.mss.mapper;

import com.trading.mss.domain.model.OrderBook;
import com.trading.mss.domain.model.ScaledDecimal;
import com.trading.mss.domain.model.SymbolState;
import com.trading.mss.message.outbound.BboMetadata;
import com.trading.mss.message.outbound.BboStateEvent;
import com.trading.mss.message.outbound.ProjectedPriceLevel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

public class BboStateMapper {

    private static final int SCHEMA_VERSION = 1;
    private static final String EVENT_TYPE = "bbo_state";

    public Optional<BboStateEvent> project(SymbolState state) {
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

        ProjectedPriceLevel bestBid = new ProjectedPriceLevel(
                ScaledDecimal.format(bestBidPrice),
                ScaledDecimal.format(book.getBids().get(bestBidPrice)));

        ProjectedPriceLevel bestAsk = new ProjectedPriceLevel(
                ScaledDecimal.format(bestAskPrice),
                ScaledDecimal.format(book.getAsks().get(bestAskPrice)));

        String spread = ScaledDecimal.format(bestAskPrice - bestBidPrice);

        String mid = BigDecimal.valueOf(bestBidPrice, ScaledDecimal.SCALE_DIGITS)
                .add(BigDecimal.valueOf(bestAskPrice, ScaledDecimal.SCALE_DIGITS))
                .divide(BigDecimal.valueOf(2), ScaledDecimal.SCALE_DIGITS, RoundingMode.HALF_UP)
                .toPlainString();

        BboMetadata metadata = new BboMetadata(
                SCHEMA_VERSION,
                EVENT_TYPE,
                state.getVenue(),
                state.getMarketType(),
                state.getSymbol(),
                state.getInstrumentId(),
                state.getLastEventExchangeTs(),
                state.getLastEventProcessedTs(),
                state.getLocalUpdateId());

        return Optional.of(new BboStateEvent(metadata, bestBid, bestAsk, spread, mid, state.isTrusted()));
    }
}
