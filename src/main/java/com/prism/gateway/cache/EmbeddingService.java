package com.prism.gateway.cache;

import com.openai.client.OpenAIClient;
import com.openai.models.embeddings.CreateEmbeddingResponse;
import com.openai.models.embeddings.EmbeddingCreateParams;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Produces embedding vectors for prompt text.
 *
 * Uses OpenRouter's OpenAI-compatible embeddings endpoint with
 * openai/text-embedding-3-small (1536 dimensions) by default. The model name is
 * configurable via prism.cache.embedding.model.
 */
@Service
public class EmbeddingService {

    public static final int DIMENSIONS = 1536;

    private final OpenAIClient client;
    private final String embeddingModel;

    public EmbeddingService(
            @Qualifier("embeddingOpenAiClient") OpenAIClient client,
            @Value("${prism.cache.embedding.model:openai/text-embedding-3-small}") String embeddingModel
    ) {
        this.client = client;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Returns the embedding vector for the given text.
     *
     * @throws RuntimeException if the embedding call fails. Callers must treat
     *         a failure as a cache miss and continue to the provider.
     */
    public float[] embed(String text) {

        EmbeddingCreateParams params = EmbeddingCreateParams.builder()
                .model(embeddingModel)
                .input(text)
                .build();

        CreateEmbeddingResponse response = client.embeddings().create(params);

        List<Float> values = response.data().getFirst().embedding();

        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i);
        }
        return vector;
    }
}
