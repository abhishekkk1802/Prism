package com.prism.gateway.service;

import com.prism.gateway.dto.ChatCompletionRequest;
import com.prism.gateway.repository.ApiKeyRepository;
import org.springframework.stereotype.Service;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static okio.HashingSink.sha256;

@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    public boolean isValid(String apiKey){
        if(apiKey == null || apiKey.isBlank())return false;

        String hash = sha256(apiKey);

        return apiKeyRepository.findActiveKey(hash).isPresent();
    }

    private String sha256(String value){
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            byte[] hash = digest.digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            StringBuilder hex = new StringBuilder();

            for(byte b : hash){
                hex.append(String.format("%02x",b));
            }

            return hex.toString();

        } catch (NoSuchAlgorithmException e){
            throw new IllegalStateException(
                    "SHA-256 algorithm not available",
                    e
            );
        }
    }
}