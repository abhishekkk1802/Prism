package com.prism.gateway.security;

import com.prism.gateway.service.ApiKeyPolicy;
import com.prism.gateway.service.ApiKeyService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class ApiKeyWebFilter implements WebFilter {

    private final ApiKeyService apiKeyService;

    public ApiKeyWebFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {

        String path = exchange.getRequest()
                .getPath()
                .value();

        if(!path.startsWith("/v1/")){
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest()
                .getHeaders()
                .getFirst("Authorization");

        if(authorization == null || !authorization.startsWith("Bearer ")){
            return unauthorized(exchange);
        }

        String apiKey = authorization.substring(7);

        var policy = apiKeyService.getPolicy(apiKey);

        if (policy.isEmpty()) {
            return unauthorized(exchange);
        }

        return chain.filter(exchange)
                .contextWrite(context ->
                        context.put(ApiKeyPolicy.class, policy.get())
                );

    }


    private Mono<Void> unauthorized(ServerWebExchange exchange){
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);

        return exchange.getResponse().setComplete();
    }
}