package com.prism.gateway.routing;

import org.springframework.stereotype.Component;

@Component
public class ProviderResolver {

    public String resolveProvider(String model) {

        int separator = model.indexOf('-');

        if (separator <= 0) {
            throw new IllegalArgumentException(
                    "Invalid model name: " + model
            );
        }

        return model.substring(0, separator);
    }
}
