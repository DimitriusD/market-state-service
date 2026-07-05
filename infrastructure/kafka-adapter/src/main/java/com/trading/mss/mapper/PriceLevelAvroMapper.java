package com.trading.mss.mapper;

import com.trading.contracts.common.PriceLevelEvent;
import com.trading.mss.dto.common.PriceLevelDto;

public final class PriceLevelAvroMapper {

    private PriceLevelAvroMapper() {}

    public static PriceLevelEvent toAvro(PriceLevelDto pl) {
        if (pl == null) {
            return null;
        }
        return new PriceLevelEvent(pl.price(), pl.qty());
    }
}
