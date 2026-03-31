package io.github.netmikey.mvncloner.mvncloner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class PublisherSmartSyncTest {

    private HttpServer server;
    private int port;
    private AtomicInteger putCount;
    private AtomicInteger headCount;

    @TempDir
    Path mirrorDir;

    @BeforeEach
    void setUp() throws IOException {
        putCount = new AtomicInteger(0);
        headCount = new AtomicInteger(0);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void skipsUploadWhenHeadReturns200() throws Exception {
        // Server responds 200 to HEAD (artifact exists) and 201 to PUT
        server.createContext("/repo/", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                headCount.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Length", "5");
                exchange.sendResponseHeaders(200, -1);
            } else if ("PUT".equals(exchange.getRequestMethod())) {
                putCount.incrementAndGet();
                exchange.sendResponseHeaders(201, -1);
            }
            exchange.close();
        });
        server.start();

        // Create a local file to "publish"
        Files.writeString(mirrorDir.resolve("test.jar"), "hello");

        Publisher publisher = createPublisher(true, true);
        publisher.publish();

        assertEquals(1, headCount.get(), "Should have sent HEAD request");
        assertEquals(0, putCount.get(), "Should NOT have sent PUT (artifact exists)");
    }

    @Test
    void uploadsWhenHeadReturns404() throws Exception {
        server.createContext("/repo/", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                headCount.incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
            } else if ("PUT".equals(exchange.getRequestMethod())) {
                putCount.incrementAndGet();
                exchange.sendResponseHeaders(201, -1);
            }
            exchange.close();
        });
        server.start();

        Files.writeString(mirrorDir.resolve("test.jar"), "hello");

        Publisher publisher = createPublisher(true, false);
        publisher.publish();

        assertEquals(1, headCount.get(), "Should have sent HEAD request");
        assertEquals(1, putCount.get(), "Should have sent PUT (artifact missing)");
    }

    @Test
    void reuploadsOnSizeMismatch() throws Exception {
        server.createContext("/repo/", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                headCount.incrementAndGet();
                // Report different size than local file
                exchange.getResponseHeaders().set("Content-Length", "999");
                exchange.sendResponseHeaders(200, -1);
            } else if ("PUT".equals(exchange.getRequestMethod())) {
                putCount.incrementAndGet();
                exchange.sendResponseHeaders(201, -1);
            }
            exchange.close();
        });
        server.start();

        Files.writeString(mirrorDir.resolve("test.jar"), "hello");

        Publisher publisher = createPublisher(true, true);
        publisher.publish();

        assertEquals(1, headCount.get());
        assertEquals(1, putCount.get(), "Should re-upload on size mismatch");
    }

    @Test
    void skipsHeadCheckWhenDisabled() throws Exception {
        server.createContext("/repo/", exchange -> {
            if ("HEAD".equals(exchange.getRequestMethod())) {
                headCount.incrementAndGet();
                exchange.sendResponseHeaders(200, -1);
            } else if ("PUT".equals(exchange.getRequestMethod())) {
                putCount.incrementAndGet();
                exchange.sendResponseHeaders(201, -1);
            }
            exchange.close();
        });
        server.start();

        Files.writeString(mirrorDir.resolve("test.jar"), "hello");

        Publisher publisher = createPublisher(false, false);
        publisher.publish();

        assertEquals(0, headCount.get(), "Should NOT send HEAD when skip-existing is off");
        assertEquals(1, putCount.get(), "Should always upload when skip-existing is off");
    }

    private Publisher createPublisher(boolean skipExisting, boolean verifySize) throws Exception {
        Publisher publisher = new Publisher();

        // Use reflection to set @Value fields for testing
        setField(publisher, "rootUrl", "http://localhost:" + port + "/repo/");
        setField(publisher, "username", null);
        setField(publisher, "password", null);
        setField(publisher, "rootMirrorPath", mirrorDir.toString());
        setField(publisher, "fileDelayMs", 0L);
        setField(publisher, "skipExisting", skipExisting);
        setField(publisher, "verifySize", verifySize);
        setField(publisher, "maxConcurrent", 2);
        setField(publisher, "retryMaxAttempts", 1);
        setField(publisher, "retryBackoffBaseMs", 10L);

        return publisher;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
