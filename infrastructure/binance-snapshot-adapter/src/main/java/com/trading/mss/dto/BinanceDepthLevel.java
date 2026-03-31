package com.trading.mss.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonFormat(shape = JsonFormat.Shape.ARRAY)
public record BinanceDepthLevel(@JsonProperty(index = 0) String price,
                                @JsonProperty(index = 1) String qty) {
}
