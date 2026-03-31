package io.github.netmikey.mvncloner.mvncloner;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes a callable with configurable retry, exponential backoff, and jitter.
 */
public class RetryExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(RetryExecutor.class);

    private final int maxAttempts;
    private final long backoffBaseMs;

    public RetryExecutor(int maxAttempts, long backoffBaseMs) {
        this.maxAttempts = maxAttempts;
        this.backoffBaseMs = backoffBaseMs;
    }

    /**
     * Execute the callable with retry logic. Retries on IOException and
     * HttpStatusException (5xx). Non-retryable exceptions propagate immediately.
     */
    public <T> T execute(Callable<T> callable, String description) throws Exception {
        Exception lastException = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return callable.call();
            } catch (Exception e) {
                lastException = e;
                if (!isRetryable(e)) {
                    throw e;
                }
                if (attempt < maxAttempts) {
                    long delay = calculateDelay(attempt);
                    LOG.warn("Attempt " + attempt + "/" + maxAttempts + " failed for " + description
                        + " (" + e.getMessage() + "), retrying in " + delay + "ms");
                    Utils.sleep(delay);
                } else {
                    LOG.error("All " + maxAttempts + " attempts failed for " + description);
                }
            }
        }

        throw lastException;
    }

    /**
     * Execute a runnable with retry logic.
     */
    public void execute(Runnable runnable, String description) throws Exception {
        execute(() -> {
            runnable.run();
            return null;
        }, description);
    }

    private boolean isRetryable(Exception e) {
        if (e instanceof IOException) {
            return true;
        }
        if (e instanceof HttpStatusException) {
            int statusCode = ((HttpStatusException) e).getStatusCode();
            return statusCode >= 500;
        }
        // Retry on wrapped IOExceptions
        if (e.getCause() instanceof IOException) {
            return true;
        }
        return false;
    }

    private long calculateDelay(int attempt) {
        long baseDelay = backoffBaseMs * (1L << (attempt - 1));
        // Add jitter: 0-50% of base delay
        long jitter = ThreadLocalRandom.current().nextLong(baseDelay / 2 + 1);
        return baseDelay + jitter;
    }

    /**
     * Simple exception to wrap HTTP status codes for retry decisions.
     */
    public static class HttpStatusException extends Exception {
        private final int statusCode;

        public HttpStatusException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }
    }
}
