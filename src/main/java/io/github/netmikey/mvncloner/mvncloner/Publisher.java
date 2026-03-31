package io.github.netmikey.mvncloner.mvncloner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes from the mirror directory into a remote target maven repository.
 *
 * @author mike
 */
@Component
public class Publisher {

    private static final Logger LOG = LoggerFactory.getLogger(Publisher.class);

    /** Sentinel path used to signal end of queue in pipeline mode. */
    static final Path POISON_PILL = Path.of("__DONE__");

    @Value("${target.root-url}")
    private String rootUrl;

    @Value("${target.user:#{null}}")
    private String username;

    @Value("${target.password:#{null}}")
    private String password;

    @Value("${mirror-path:./mirror/}")
    private String rootMirrorPath;

    @Value("${sync.publisher.file-delay-ms:1000}")
    private long fileDelayMs;

    @Value("${sync.publisher.skip-existing:true}")
    private boolean skipExisting;

    @Value("${sync.publisher.verify-size:true}")
    private boolean verifySize;

    @Value("${sync.publisher.max-concurrent:8}")
    private int maxConcurrent;

    @Value("${sync.retry.max-attempts:3}")
    private int retryMaxAttempts;

    @Value("${sync.retry.backoff-base-ms:1000}")
    private long retryBackoffBaseMs;

    private RetryExecutor retryExecutor;
    private HttpClient httpClient;
    private ExecutorService executor;
    private Semaphore semaphore;

    /**
     * Publish all files from the mirror directory to the target repository.
     */
    public void publish() throws Exception {
        LOG.info("Publishing to " + rootUrl + " (max concurrent: " + maxConcurrent + ") ...");
        initClient();
        try {
            publishDirectory(rootUrl, Paths.get(rootMirrorPath).normalize());
        } finally {
            executor.close();
        }
        LOG.info("Publishing complete.");
    }

    /**
     * Publish files received via a blocking queue (pipeline mode).
     * Reads paths from the queue until the POISON_PILL sentinel is received.
     */
    public void publishFromQueue(BlockingQueue<Path> queue) throws Exception {
        LOG.info("Publishing to " + rootUrl + " (pipeline mode, max concurrent: " + maxConcurrent + ") ...");
        initClient();
        Path mirrorRoot = Paths.get(rootMirrorPath).normalize();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        try {
            while (true) {
                Path filePath = queue.poll(30, TimeUnit.SECONDS);
                if (filePath == null) {
                    // Timeout waiting for next item — keep waiting
                    // (scraper may be slow on a large directory)
                    continue;
                }
                if (filePath.equals(POISON_PILL)) {
                    break;
                }
                // Compute the target URL from the relative path
                Path relativePath = mirrorRoot.relativize(filePath);
                String targetUrl = rootUrl;
                if (!targetUrl.endsWith("/")) {
                    targetUrl += "/";
                }
                targetUrl += relativePath.toString().replace('\\', '/');

                final String url = targetUrl;
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        semaphore.acquire();
                        try {
                            retryExecutor.execute(
                                () -> { uploadFile(url, filePath); return null; },
                                "upload " + filePath.getFileName());
                        } finally {
                            semaphore.release();
                        }
                    } catch (Exception e) {
                        LOG.error("Failed to upload " + filePath.getFileName() + " after retries: " + e.getMessage(), e);
                    }
                }, executor);
                futures.add(future);
            }

            // Wait for remaining uploads to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        } finally {
            executor.close();
        }
        LOG.info("Publishing complete.");
    }

    private void initClient() {
        retryExecutor = new RetryExecutor(retryMaxAttempts, retryBackoffBaseMs);
        httpClient = HttpClient.newBuilder().build();
        executor = Executors.newVirtualThreadPerTaskExecutor();
        semaphore = new Semaphore(maxConcurrent);
    }

    private void publishDirectory(String repositoryUrl, Path mirrorPath) throws Exception {
        LOG.debug("Switching to mirror directory: " + mirrorPath.toAbsolutePath());

        List<Path> recursePaths = new ArrayList<>();
        List<CompletableFuture<Void>> uploadFutures = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mirrorPath)) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) {
                    recursePaths.add(path);
                } else {
                    String targetUrl = repositoryUrl + path.getFileName().toString();
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            semaphore.acquire();
                            try {
                                retryExecutor.execute(
                                    () -> { uploadFile(targetUrl, path); return null; },
                                    "upload " + path.getFileName());
                            } finally {
                                semaphore.release();
                            }
                        } catch (Exception e) {
                            LOG.error("Failed to upload " + path.getFileName() + " after retries: " + e.getMessage(), e);
                        }
                    }, executor);
                    uploadFutures.add(future);
                }
            }
        }

        // Wait for all uploads in this directory
        CompletableFuture.allOf(uploadFutures.toArray(new CompletableFuture[0])).join();

        // Process subdirectories concurrently
        List<CompletableFuture<Void>> dirFutures = new ArrayList<>();
        for (Path recursePath : recursePaths) {
            String subpath = mirrorPath.relativize(recursePath).toString();
            String subUrl = appendUrlPathSegment(repositoryUrl, subpath);
            CompletableFuture<Void> dirFuture = CompletableFuture.runAsync(() -> {
                try {
                    publishDirectory(subUrl, recursePath);
                } catch (Exception e) {
                    LOG.error("Failed to publish directory " + recursePath + " : " + e.getMessage(), e);
                }
            }, executor);
            dirFutures.add(dirFuture);
        }

        CompletableFuture.allOf(dirFutures.toArray(new CompletableFuture[0])).join();
    }

    private void uploadFile(String targetUrl, Path path) throws Exception {
        // Check if artifact already exists on target
        if (skipExisting) {
            HttpRequest headRequest = Utils.setCredentials(HttpRequest.newBuilder(), username, password)
                .uri(URI.create(targetUrl))
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

            HttpResponse<Void> headResponse = httpClient.send(headRequest, BodyHandlers.discarding());
            if (headResponse.statusCode() >= 200 && headResponse.statusCode() <= 299) {
                if (verifySize) {
                    long remoteSize = headResponse.headers().firstValueAsLong("Content-Length").orElse(-1);
                    long localSize = Files.size(path);
                    if (remoteSize >= 0 && remoteSize != localSize) {
                        LOG.info("Size mismatch for " + targetUrl + " (local=" + localSize
                            + ", remote=" + remoteSize + "), re-uploading");
                    } else {
                        LOG.debug("Skipping (already exists): " + targetUrl);
                        return;
                    }
                } else {
                    LOG.debug("Skipping (already exists): " + targetUrl);
                    return;
                }
            }
        }

        LOG.info("Uploading " + targetUrl);

        if (fileDelayMs > 0) {
            Utils.sleep(fileDelayMs);
        }
        HttpRequest request = Utils.setCredentials(HttpRequest.newBuilder(), username, password)
            .uri(URI.create(targetUrl))
            .PUT(BodyPublishers.ofInputStream(() -> {
                try {
                    return Files.newInputStream(path, StandardOpenOption.READ);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }))
            .build();
        HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() > 299) {
            LOG.debug("   Response headers: " + response.headers());
            LOG.debug("   Response body: " + response.body());
            throw new RetryExecutor.HttpStatusException(response.statusCode(),
                "HTTP " + response.statusCode() + " uploading " + targetUrl);
        }
    }

    private String appendUrlPathSegment(String baseUrl, String segment) {
        StringBuffer result = new StringBuffer(baseUrl);

        if (!baseUrl.endsWith("/")) {
            result.append('/');
        }
        result.append(segment);
        result.append('/');

        return result.toString();
    }
}
