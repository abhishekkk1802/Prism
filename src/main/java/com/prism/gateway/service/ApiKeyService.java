package com.prism.gateway.service;

import com.prism.gateway.dto.ChatCompletionRequest;
import com.prism.gateway.repository.ApiKeyRepository;
import com.prism.gateway.security.TokenHasher;
import org.springframework.stereotype.Service;

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
        // "auto" is a routing alias.
        // It is allowed only when the key can access
        // all tiers that auto may route to.
        if ("auto".equals(model)) {
            return policy.allowedModels().contains("fast")
                    && policy.allowedModels().contains("smart");
        }

        return policy.allowedModels().contains(model);
    }

    private String sha256(String value){
        return TokenHasher.sha256(value);
    }

    public Optional<ApiKeyDetails> getActiveKey(String apiKey) {

        if (apiKey == null || apiKey.isBlank()) {
            return Optional.empty();
        }

        String hash = sha256(apiKey);

        return apiKeyRepository.findActiveKeyDetails(hash);
    }
}