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


@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private final ChatCompletionService chatCompletionService;

    public ChatCompletionController(ChatCompletionService chatCompletionService) {
        this.chatCompletionService = chatCompletionService;
    }


    @PostMapping("/chat/completions")
    public ResponseEntity<ChatCompletionResponse> chatCompletionResponse(
            @Valid @RequestBody ChatCompletionRequest request
            ){
        ProviderExecutionResult result = chatCompletionService.complete(request);

        return ResponseEntity.ok()
                .header("x-prism-provider", result.provider())
                .header("x-prism-model", result.model())
                .header("x-prism-request-model", request.model())
                .body(result.response());

    }

    @PostMapping(
            value = "/chat/completions/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public ResponseEntity<Flux<String>> chatCompletionStream(
            @Valid @RequestBody ChatCompletionRequest request
    ) {
        ProviderStreamResult result = chatCompletionService.stream(request);

        return ResponseEntity.ok()
                .header("x-prism-provider", result.provider())
                .header("x-prism-model", result.model())
                .header("x-prism-request-model", request.model())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(result.stream());
    }

    private String extractApiKey(String authorization){
        if(authorization==null || !authorization.startsWith("Bearer "))return null;

        return authorization.substring(7);
    }
}