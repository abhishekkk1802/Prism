package com.prism.gateway.controller;

import com.prism.gateway.admin.AdminService;
import com.prism.gateway.admin.dto.AdminLogEntryResponse;
import com.prism.gateway.admin.dto.AdminUsageBreakdownResponse;
import com.prism.gateway.admin.dto.AdminUsageResponse;
import com.prism.gateway.cache.SemanticCacheService;
import com.prism.gateway.ops.dto.ProviderHealthResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Management API (Prism plan sections 1, 4, 9).
 *
 * Guarded by {@link com.prism.gateway.security.AdminWebFilter} — a separate
 * admin token, entirely independent of per-team API keys. Every endpoint here
 * delegates to {@link AdminService}, which itself reuses the same
 * repositories/services the data-plane /v1/* endpoints already use (no
 * duplicated metric logic).
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private static final int DEFAULT_LOOKBACK_DAYS = 30;
    private static final int DEFAULT_LOG_LIMIT = 100;
    private static final int MAX_LOG_LIMIT = 1000;
    private static final int DEFAULT_PROVIDER_HEALTH_HOURS = 24;

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/usage")
    public ResponseEntity<AdminUsageResponse> usage(
            @RequestParam String key,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        Instant resolvedTo = to != null ? to : Instant.now();
        Instant resolvedFrom = from != null ? from : resolvedTo.minus(DEFAULT_LOOKBACK_DAYS, ChronoUnit.DAYS);
        return ResponseEntity.ok(adminService.usage(key, resolvedFrom, resolvedTo));
    }

    @GetMapping("/logs")
    public ResponseEntity<List<AdminLogEntryResponse>> logs(
            @RequestParam String key,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer limit
    ) {
        int resolvedLimit = clampLimit(limit);
        return ResponseEntity.ok(
                adminService.logs(key, provider, model, status, from, to, resolvedLimit)
        );
    }

    @GetMapping("/logs/{requestId}")
    public ResponseEntity<AdminLogEntryResponse> logDetail(@PathVariable String requestId) {
        return adminService.logByRequestId(requestId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/cache/stats")
    public ResponseEntity<SemanticCacheService.CacheStats> cacheStats(@RequestParam String key) {
        return ResponseEntity.ok(adminService.cacheStats(key));
    }

    @GetMapping("/providers/health")
    public ResponseEntity<ProviderHealthResponse> providersHealth(
            @RequestParam(required = false) Integer hours
    ) {
        int window = (hours == null || hours <= 0) ? DEFAULT_PROVIDER_HEALTH_HOURS : hours;
        return ResponseEntity.ok(adminService.providersHealth(window));
    }

    @GetMapping("/usage/breakdown")
    public ResponseEntity<AdminUsageBreakdownResponse> usageBreakdown(
            @RequestParam String key,
            @RequestParam String groupBy,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        Instant resolvedTo = to != null ? to : Instant.now();
        Instant resolvedFrom = from != null ? from : resolvedTo.minus(DEFAULT_LOOKBACK_DAYS, ChronoUnit.DAYS);
        return ResponseEntity.ok(adminService.usageBreakdown(key, groupBy, resolvedFrom, resolvedTo));
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LOG_LIMIT;
        }
        return Math.min(limit, MAX_LOG_LIMIT);
    }
}
