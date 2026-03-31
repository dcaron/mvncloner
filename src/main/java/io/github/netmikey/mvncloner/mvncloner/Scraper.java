package io.github.netmikey.mvncloner.mvncloner;

import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.Connection;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Component that scrapes content from a remote maven repository using the
 * "index"-style HTML content pages and mirrors it onto the local filesystem.
 *
 * @author mike
 */
@Component
public class Scraper {

    private static final Logger LOG = LoggerFactory.getLogger(Scraper.class);

    private static final Pattern FILE_URL_PATTERN = Pattern.compile("^.*/([^/]+\\.([^\\./]{1,6}))$");

    private static final Set<String> EXTENSION_BLACKLIST = new HashSet<>(
        Arrays.asList("md5", "sha1", "asc", "sha256", "sha512"));

    private static final Set<String> FILENAME_BLACKLIST = new HashSet<>(
        Arrays.asList("maven-metadata.xml", "archetype-catalog.xml", "nexus-maven-repository-index.gz"));

    @Value("${source.root-url}")
    private String rootUrl;

    @Value("${source.user:#{null}}")
    private String username;

    @Value("${source.password:#{null}}")
    private String password;

    @Value("${mirror-path:./mirror/}")
    private String rootMirrorPath;

    @Value("${sync.scraper.file-delay-ms:500}")
    private long fileDelayMs;

    @Value("${sync.scraper.directory-delay-ms:1000}")
    private long directoryDelayMs;

    @Value("${sync.scraper.max-concurrent:6}")
    private int maxConcurrent;

    @Value("${sync.retry.max-attempts:3}")
    private int retryMaxAttempts;

    @Value("${sync.retry.backoff-base-ms:1000}")
    private long retryBackoffBaseMs;

    private HttpClient httpClient;
    private RetryExecutor retryExecutor;
    private ExecutorService executor;
    private Semaphore semaphore;
    private Consumer<Path> downloadCallback;

    public void mirror() throws Exception {
        mirror(null);
    }

    public void mirror(Consumer<Path> onFileDownloaded) throws Exception {
        httpClient = HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        retryExecutor = new RetryExecutor(retryMaxAttempts, retryBackoffBaseMs);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        semaphore = new Semaphore(maxConcurrent);
        downloadCallback = onFileDownloaded;

        LOG.info("Mirroring from " + rootUrl + " (max concurrent: " + maxConcurrent + ") ...");
        try {
            processIndexUrl(rootUrl, Paths.get(rootMirrorPath));
        } finally {
            executor.close();
        }
        LOG.info("Download complete.");
    }

    private void processIndexUrl(String pageUrl, Path mirrorPath)
        throws IOException, URISyntaxException, InterruptedException {
        LOG.debug("Switching to mirror directory: " + mirrorPath.toAbsolutePath());
        Files.createDirectories(mirrorPath);

        LOG.debug("Getting source repo URL: " + pageUrl);

        // Acquire semaphore for the HTTP request to fetch the index page
        Document doc;
        semaphore.acquire();
        try {
            Connection connection = Jsoup.connect(pageUrl)
                .ignoreContentType(false)
                .timeout(30_000);

            if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)) {
                String sourceHost = URI.create(rootUrl).getHost();
                if (URI.create(pageUrl).getHost().equals(sourceHost)) {
                    connection.header("Authorization", Utils.authorizationHeaderValue(username, password));
                }
            }

            doc = connection.get();
        } catch (org.jsoup.HttpStatusException e) {
            LOG.debug("   Not an HTML index page or error, skipping: " + pageUrl + " (HTTP " + e.getStatusCode() + ")");
            return;
        } catch (org.jsoup.UnsupportedMimeTypeException e) {
            LOG.debug("   Not an HTML page, skipping: " + pageUrl);
            return;
        } finally {
            semaphore.release();
        }

        List<String> recurseUrls = new ArrayList<>();
        List<CompletableFuture<Void>> downloadFutures = new ArrayList<>();
        String pageHost = URI.create(pageUrl).getHost();

        Elements links = doc.select("a[href]");
        for (Element link : links) {
            String href = link.attr("abs:href");
            if (href.isEmpty()) {
                href = link.attr("href");
                if (!href.startsWith("http")) {
                    href = URI.create(pageUrl).resolve(href).toString();
                }
            }
            LOG.trace("   Found link: " + href);

            if (URI.create(href).getHost().equals(pageHost)) {
                Matcher filePatternMatcher = FILE_URL_PATTERN.matcher(href);
                if (filePatternMatcher.matches()) {
                    String fileUrl = filePatternMatcher.group(0);
                    String fileName = filePatternMatcher.group(1);
                    String fileExt = filePatternMatcher.group(2);

                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        try {
                            semaphore.acquire();
                            try {
                                retryExecutor.execute(
                                    () -> { downloadFile(mirrorPath, fileUrl, fileName, fileExt); return null; },
                                    "download " + fileUrl);
                            } finally {
                                semaphore.release();
                            }
                        } catch (Exception e) {
                            LOG.error("Failed to download " + fileUrl + " : " + e.getMessage(), e);
                        }
                    }, executor);
                    downloadFutures.add(future);
                } else {
                    if (href.startsWith(pageUrl)) {
                        LOG.trace("      Mark for recursion.");
                        recurseUrls.add(href);
                    } else {
                        LOG.trace("      Ignoring this link: destination outside of scope.");
                    }
                }
            } else {
                LOG.trace("      Ignoring this link: destination outside of scope.");
            }
        }

        // Wait for all file downloads in this directory to complete
        CompletableFuture.allOf(downloadFutures.toArray(new CompletableFuture[0])).join();

        // Process subdirectories concurrently
        List<CompletableFuture<Void>> dirFutures = new ArrayList<>();
        for (String fullyQualifiedUrl : recurseUrls) {
            URI base = new URI(pageUrl);
            String relativePath = StringUtils.strip(base.relativize(new URI(fullyQualifiedUrl)).toString(), "/ ");
            LOG.debug("   Recursing into: " + relativePath);

            final String dirUrl = fullyQualifiedUrl;
            final Path dirPath = mirrorPath.resolve(relativePath);
            CompletableFuture<Void> dirFuture = CompletableFuture.runAsync(() -> {
                try {
                    if (directoryDelayMs > 0) {
                        Utils.sleep(directoryDelayMs);
                    }
                    processIndexUrl(dirUrl, dirPath);
                } catch (Exception e) {
                    LOG.error("Failed to process directory " + dirUrl + " : " + e.getMessage(), e);
                }
            }, executor);
            dirFutures.add(dirFuture);
        }

        CompletableFuture.allOf(dirFutures.toArray(new CompletableFuture[0])).join();
    }

    private void downloadFile(Path mirrorPath, String fullyQualifiedUrl, String filename, String extension)
        throws Exception {

        if (FILENAME_BLACKLIST.contains(filename.toLowerCase())) {
            LOG.trace("      Ignoring this link: filename in blacklist: " + filename);
        } else if (EXTENSION_BLACKLIST.contains(extension.toLowerCase())) {
            LOG.trace("      Ignoring this link: extension in blacklist: " + extension);
        } else {
            Path targetFile = mirrorPath.resolve(filename);
            if (Files.exists(targetFile)) {
                LOG.debug("      File already exists, skipping download: " + targetFile);
            } else {
                if (fileDelayMs > 0) {
                    Utils.sleep(fileDelayMs);
                }
                LOG.info("      Downloading: " + fullyQualifiedUrl);

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(fullyQualifiedUrl))
                    .GET();

                String sourceHost = URI.create(rootUrl).getHost();
                if (URI.create(fullyQualifiedUrl).getHost().equals(sourceHost)) {
                    Utils.setCredentials(requestBuilder, username, password);
                }

                HttpResponse<InputStream> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());

                int statusCode = response.statusCode();
                if (statusCode < 200 || statusCode > 299) {
                    throw new IOException("Server returned HTTP " + statusCode + " for " + fullyQualifiedUrl);
                }

                Path tmpFile = mirrorPath.resolve(filename + ".tmp");
                try (InputStream body = response.body()) {
                    Files.copy(body, tmpFile);
                    Files.move(tmpFile, targetFile, StandardCopyOption.ATOMIC_MOVE);
                } catch (Exception e) {
                    Files.deleteIfExists(tmpFile);
                    throw e;
                }
                LOG.debug("         ... done.");

                if (downloadCallback != null) {
                    downloadCallback.accept(targetFile);
                }
            }
        }
    }
}
