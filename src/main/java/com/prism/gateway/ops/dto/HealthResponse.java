package com.prism.gateway.ops.dto;

/**
 * Gateway liveness indicator.
 */
public record HealthResponse(String status) {
    public static HealthResponse up() {
        return new HealthResponse("UP");
    }
}
