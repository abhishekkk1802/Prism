package com.prism.gateway.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

@Component
public class ProviderRetryPolicy {

    public boolean isRetryable(Throwable error) {

        Throwable cause = unwrap(error);

        // Network / connection failures
        if (cause instanceof IOException
                || cause instanceof ConnectException
                || cause instanceof SocketTimeoutException
                || cause instanceof TimeoutException) {
            return true;
        }

        // HTTP status based failures
        Integer status = extractHttpStatus(cause);

        if (status != null) {
            return status == 429
                    || status == 500
                    || status == 502
                    || status == 503
                    || status == 504;
        }

        return false;
    }

    private Throwable unwrap(Throwable error) {

        Throwable current = error;

        while (current.getCause() != null
                && current.getCause() != current) {

            current = current.getCause();
        }

        return current;
    }

    private Integer extractHttpStatus(Throwable error) {

        // We will add provider-SDK-specific status extraction
        // here when needed.

        return null;
    }
}
