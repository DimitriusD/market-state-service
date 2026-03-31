package com.trading.mss.dto;

import java.util.List;

public record BinanceDepthResponse(long lastUpdateId,
                                   List<BinanceDepthLevel> bids,
                                   List<BinanceDepthLevel> asks) {

}
