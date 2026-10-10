package com.prism.gateway.config;

import com.prism.gateway.config.model.GatewayConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Configuration
public class GatewayConfigLoader {

    /**
     * Optional external override path (plan deployment shape: docker-compose
     * mounts a variant pointing providers at Compose service names instead of
     * localhost, with zero code/jar changes). Falls back to the bundled
     * classpath resource when unset or the file doesn't exist.
     */
    @Value("${prism.gateway-config.path:}")
    private String externalConfigPath;

    /**
     * Matches ${ENV_VAR} and ${ENV_VAR:default} placeholders so secrets like a
     * provider api_key can be injected from the environment instead of being
     * written into the committed config file.
     */
    private static final Pattern ENV_PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Za-z0-9_]+)(?::([^}]*))?}");

    @Bean
    public GatewayConfig gatewayConfig(ObjectMapper objectMapper) throws IOException {

        String raw;
        try (InputStream in = resolveConfigStream()) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return objectMapper.readValue(expandEnvPlaceholders(raw), GatewayConfig.class);
    }

    private InputStream resolveConfigStream() throws IOException {
        if (externalConfigPath != null && !externalConfigPath.isBlank()) {
            File external = new File(externalConfigPath);
            if (external.isFile()) {
                return new FileSystemResource(external).getInputStream();
            }
        }
        return new ClassPathResource("gateway-config.json").getInputStream();
    }

    /**
     * Replaces ${VAR} / ${VAR:default} with the environment variable value
     * (or the default, or an empty string). Config without placeholders is
     * returned unchanged, so existing mock configs are unaffected.
     */
    static String expandEnvPlaceholders(String content) {
        Matcher matcher = ENV_PLACEHOLDER.matcher(content);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String envName = matcher.group(1);
            String fallback = matcher.group(2) != null ? matcher.group(2) : "";
            String value = System.getenv(envName);
            String replacement = (value != null && !value.isEmpty()) ? value : fallback;
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
