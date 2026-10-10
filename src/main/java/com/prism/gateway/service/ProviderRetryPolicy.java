package com.prism.gateway.service;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

@Component
public class ProviderRetryPolicy {

    public boolean isRetryable(Throwable error) {

        // Walk the full cause chain once, checking each level
        Throwable current = error;
        while (current != null) {

            // SDK explicit retryable marker
            if (current instanceof OpenAIRetryableException) {
                return true;
            }

            // SDK IO / network failure
            if (current instanceof OpenAIIoException) {
                return true;
            }

            // SDK HTTP error — check status code
            if (current instanceof OpenAIServiceException httpEx) {
                int status = httpEx.statusCode();
                return status == 429
                        || status == 500
                        || status == 502
                        || status == 503
                        || status == 504;
            }

            // Raw network / connection failures
            if (current instanceof ConnectException
                    || current instanceof SocketTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }

            // Generic IO (catches remaining network issues)
            if (current instanceof IOException) {
                return true;
            }

            Throwable cause = current.getCause();
            current = (cause != current) ? cause : null;
        }

        return false;
    }
}
