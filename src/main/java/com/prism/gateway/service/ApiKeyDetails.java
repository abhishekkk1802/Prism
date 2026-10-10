package com.prism.gateway.service;

import java.util.UUID;

public record ApiKeyDetails(
        UUID id,
        int rpmLimit
) {
}