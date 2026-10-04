package com.prism.gateway.controller;


import com.prism.gateway.dto.ChatCompletionRequest;
import com.prism.gateway.dto.ChatCompletionResponse;
import com.prism.gateway.service.ChatCompletionService;
import com.prism.gateway.service.ProviderExecutionResult;
import com.prism.gateway.service.ProviderStreamResult;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private final ChatCompletionService chatCompletionService;

    public ChatCompletionController(ChatCompletionService chatCompletionService) {
        this.chatCompletionService = chatCompletionService;
    }


//    @PostMapping("/chat/completions")
//    public Mono<ResponseEntity<ChatCompletionResponse>> chatCompletionResponse(
//            @Valid @RequestBody ChatCompletionRequest request
//            ){
//        ProviderExecutionResult result = chatCompletionService.complete(request).block();
//
//        return ResponseEntity.ok()
//                .header("x-prism-provider", result.provider())
//                .header("x-prism-model", result.model())
//                .header("x-prism-request-model", request.model())
//                .body(result.response());
//
//    }

    @PostMapping("/chat/completions")
    public Mono<ResponseEntity<ChatCompletionResponse>> chatCompletionResponse(
            @Valid @RequestBody ChatCompletionRequest request
    ) {
        return chatCompletionService.complete(request)
                .map(result ->
                        ResponseEntity.ok()
                                .header("x-prism-provider", result.provider())
                                .header("x-prism-model", result.model())
                                .header("x-prism-request-model", request.model())
                                .header("x-prism-cost-usd", result.costUsd() != null ? result.costUsd().toPlainString() : "")
                                .body(result.response())
                );
    }

    @PostMapping(
            value = "/chat/completions/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public Mono<ResponseEntity<Flux<String>>> chatCompletionStream(
            @Valid @RequestBody ChatCompletionRequest request
    ) {
        return chatCompletionService.stream(request)
                .map(result -> ResponseEntity.ok()
                        .header("x-prism-provider", result.provider())
                        .header("x-prism-model", result.model())
                        .header("x-prism-request-model", request.model())
                        .header("x-prism-fallback", String.valueOf(result.fallback()))
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body(result.stream()));
    }

    private String extractApiKey(String authorization){
        if(authorization==null || !authorization.startsWith("Bearer "))return null;

        return authorization.substring(7);
    }
}