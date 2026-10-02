package com.prism.gateway.controller;


import com.prism.gateway.dto.ChatCompletionRequest;
import com.prism.gateway.dto.ChatCompletionResponse;
import com.prism.gateway.service.ChatCompletionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private final ChatCompletionService chatCompletionService;

    public ChatCompletionController(ChatCompletionService chatCompletionService) {
        this.chatCompletionService = chatCompletionService;
    }


    @PostMapping("/chat/completions")
    public ChatCompletionResponse chatCompletionResponse(
            @Valid @RequestBody ChatCompletionRequest request
            ){
        ChatCompletionResponse response = chatCompletionService.complete(request);

        return ResponseEntity.ok()
                .header("x-prism-model", response.model())
                .body(response).getBody();
    }

    private String extractApiKey(String authorization){
        if(authorization==null || !authorization.startsWith("Bearer "))return null;

        return authorization.substring(7);
    }
}