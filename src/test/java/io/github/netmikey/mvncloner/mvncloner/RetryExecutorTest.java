package io.github.netmikey.mvncloner.mvncloner;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RetryExecutorTest {

    @Test
    void succeedsOnFirstAttempt() throws Exception {
        RetryExecutor executor = new RetryExecutor(3, 10);
        String result = executor.execute(() -> "ok", "test");
        assertEquals("ok", result);
    }

    @Test
    void retriesOnIOExceptionThenSucceeds() throws Exception {
        RetryExecutor executor = new RetryExecutor(3, 10);
        AtomicInteger attempts = new AtomicInteger(0);

        String result = executor.execute(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IOException("transient failure");
            }
            return "recovered";
        }, "test");

        assertEquals("recovered", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void retriesOn5xxHttpStatus() throws Exception {
        RetryExecutor executor = new RetryExecutor(3, 10);
        AtomicInteger attempts = new AtomicInteger(0);

        String result = executor.execute(() -> {
            if (attempts.incrementAndGet() < 2) {
                throw new RetryExecutor.HttpStatusException(503, "Service Unavailable");
            }
            return "recovered";
        }, "test");

        assertEquals("recovered", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void doesNotRetryOn4xxHttpStatus() {
        RetryExecutor executor = new RetryExecutor(3, 10);
        AtomicInteger attempts = new AtomicInteger(0);

        RetryExecutor.HttpStatusException thrown = assertThrows(
            RetryExecutor.HttpStatusException.class,
            () -> executor.execute(() -> {
                attempts.incrementAndGet();
                throw new RetryExecutor.HttpStatusException(404, "Not Found");
            }, "test"));

        assertEquals(404, thrown.getStatusCode());
        assertEquals(1, attempts.get(), "Should not retry on 4xx");
    }

    @Test
    void doesNotRetryOnNonRetryableException() {
        RetryExecutor executor = new RetryExecutor(3, 10);
        AtomicInteger attempts = new AtomicInteger(0);

        assertThrows(IllegalArgumentException.class,
            () -> executor.execute(() -> {
                attempts.incrementAndGet();
                throw new IllegalArgumentException("bad input");
            }, "test"));

        assertEquals(1, attempts.get(), "Should not retry non-retryable exceptions");
    }

    @Test
    void exhaustsAllAttemptsAndThrowsLastException() {
        RetryExecutor executor = new RetryExecutor(3, 10);
        AtomicInteger attempts = new AtomicInteger(0);

        IOException thrown = assertThrows(IOException.class,
            () -> executor.execute(() -> {
                attempts.incrementAndGet();
                throw new IOException("persistent failure");
            }, "test"));

        assertEquals(3, attempts.get());
        assertEquals("persistent failure", thrown.getMessage());
    }

    @Test
    void retriesOnWrappedIOException() throws Exception {
        RetryExecutor executor = new RetryExecutor(3, 10);
        AtomicInteger attempts = new AtomicInteger(0);

        String result = executor.execute(() -> {
            if (attempts.incrementAndGet() < 2) {
                throw new RuntimeException(new IOException("wrapped"));
            }
            return "ok";
        }, "test");

        assertEquals("ok", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void runnableVariantWorks() throws Exception {
        RetryExecutor executor = new RetryExecutor(3, 10);
        AtomicInteger counter = new AtomicInteger(0);

        executor.execute(() -> counter.incrementAndGet(), "test");

        assertEquals(1, counter.get());
    }

    @Test
    void singleAttemptMeansNoRetry() {
        RetryExecutor executor = new RetryExecutor(1, 10);
        AtomicInteger attempts = new AtomicInteger(0);

        assertThrows(IOException.class,
            () -> executor.execute(() -> {
                attempts.incrementAndGet();
                throw new IOException("fail");
            }, "test"));

        assertEquals(1, attempts.get());
    }
}
