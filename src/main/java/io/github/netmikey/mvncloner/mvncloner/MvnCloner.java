package io.github.netmikey.mvncloner.mvncloner;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * @author mike
 */
@Component
public class MvnCloner implements CommandLineRunner {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(MvnCloner.class);

    @Autowired
    private Scraper scraper;

    @Autowired
    private Publisher publisher;

    @Value("${actions:mirror,publish}")
    private Set<String> actions;

    @Override
    public void run(String... args) throws Exception {
        boolean doMirror = actions.contains("mirror");
        boolean doPublish = actions.contains("publish");

        if (doMirror && doPublish) {
            runPipeline();
        } else {
            if (doMirror) {
                scraper.mirror();
            }
            if (doPublish) {
                publisher.publish();
            }
        }

        LOG.info("Done.");
    }

    private void runPipeline() throws Exception {
        LOG.info("Running in pipeline mode: mirror + publish concurrently.");
        LinkedBlockingQueue<Path> queue = new LinkedBlockingQueue<>();

        CompletableFuture<Void> publishFuture = CompletableFuture.runAsync(() -> {
            try {
                publisher.publishFromQueue(queue);
            } catch (Exception e) {
                LOG.error("Publisher failed: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
        });

        Exception scraperException = null;
        try {
            scraper.mirror(path -> {
                try {
                    queue.put(path);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        } catch (Exception e) {
            scraperException = e;
            LOG.error("Scraper failed: " + e.getMessage(), e);
        } finally {
            // Always signal the publisher to stop, even if scraper failed.
            // Retry in a loop to handle InterruptedException on the put itself.
            boolean sent = false;
            for (int i = 0; i < 3 && !sent; i++) {
                try {
                    sent = queue.offer(Publisher.POISON_PILL, 5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!sent) {
                LOG.error("Failed to send shutdown signal to publisher — it may hang.");
            }
        }

        // Wait for publisher to finish processing remaining items
        try {
            publishFuture.join();
        } catch (Exception e) {
            LOG.error("Publisher encountered an error: " + e.getMessage(), e);
        }

        if (scraperException != null) {
            throw scraperException;
        }
    }
}
