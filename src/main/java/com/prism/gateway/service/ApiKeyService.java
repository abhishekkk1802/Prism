package com.prism.gateway.service;

import com.prism.gateway.dto.ChatCompletionRequest;
import com.prism.gateway.repository.ApiKeyRepository;
import org.springframework.stereotype.Service;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    public Optional<ApiKeyPolicy> getPolicy(String apiKey){
        if(apiKey == null || apiKey.isBlank()) return Optional.empty();

        String hash = sha256(apiKey);

        return apiKeyRepository.findActivePolicy(hash);
    }

    public boolean isValid(String apiKey){
        return getPolicy(apiKey).isPresent();
    }

    public boolean isModelAllowed(ApiKeyPolicy policy, String model) {

        if (policy.allowedModels() == null ||
                policy.allowedModels().isEmpty()) {
            return true;
        }

        return policy.allowedModels().contains(model);
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