package com.trading.mss.port.output;

import com.trading.mss.domain.model.SymbolState;

public interface SymbolStateStorePort {

    SymbolState loadOrCreate(String symbol, String venue);

    void save(SymbolState state);
}
