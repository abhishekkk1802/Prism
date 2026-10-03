package com.prism.gateway.service;

import reactor.core.publisher.Flux;

public record ProviderStreamResult(
        Flux<String> stream,
        String provider,
        String model
) {
}