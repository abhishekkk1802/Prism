package com.prism.gateway.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Plain liveness check: shows the gateway process is running.
 * Public, no secrets. For readiness (database/Redis reachable), use
 * Spring Boot Actuator's /actuator/health/readiness instead.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
