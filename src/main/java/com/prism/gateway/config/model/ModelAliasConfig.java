package com.prism.gateway.config.model;

import java.util.List;
import java.util.Map;

public record ModelAliasConfig(
        String primary,
        List<String> fallbacks,
        Map<String, String> route_by_difficulty
) {
}
