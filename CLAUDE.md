# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build the fat JAR (requires Java 21+)
./gradlew bootJar

# Run tests
./gradlew test

# Run the jar directly
java -jar build/libs/mvncloner-*.jar \
    --source.root-url=https://source/repo/ \
    --target.root-url=https://target/repo/ \
    --target.user=admin \
    --target.password=secret
```

The `ManualTest` in the test tree is `@Disabled` by design — remove the annotation to run it manually from an IDE with real credentials.

## Docker / Scheduled Deployment

```bash
cp .env.example .env   # fill in credentials and SCHEDULE
docker compose up -d
```

The compose stack includes:
- **nexus**: local Nexus 3 target repository
- **mvncloner**: one-shot container that runs the tool
- **ofelia**: cron scheduler that re-runs `mvncloner` on the configured `SCHEDULE`

Use `http://nexus:8081/...` (not `localhost`) for `TARGET_MAVEN_URL` inside Docker.

## Architecture

The tool is a Spring Boot CLI app (`CommandLineRunner`) built on Java 21 with virtual threads for concurrent I/O.

| Class | Role |
|---|---|
| `MvnCloner` | Entry point; reads `actions` property and delegates to `Scraper` and/or `Publisher`. When both are active, runs them in **pipeline mode** (concurrent via `BlockingQueue`). |
| `Scraper` | Crawls the source repo's HTML index pages (via Jsoup), downloads artifact files to `mirror-path` on disk using `java.net.http.HttpClient`. Skips checksum/signature files and metadata files. Uses virtual threads with semaphore-based throttling for concurrent downloads. |
| `Publisher` | Walks `mirror-path` recursively and HTTP PUTs each file to the target repo. Issues a HEAD request first to skip artifacts that already exist (smart sync). Uses virtual threads with semaphore-based throttling. |
| `RetryExecutor` | Wraps callables with configurable retry, exponential backoff, and jitter. Retries on `IOException` and HTTP 5xx; fails fast on 4xx. |
| `Utils` | Shared Basic-auth header builder and failsafe sleep. |

**Pipeline mode:** When both `mirror` and `publish` actions are enabled, the scraper feeds downloaded file paths to the publisher via a `LinkedBlockingQueue`. Uploads begin as soon as the first files download — no waiting for the full mirror to complete.

**Smart sync:** Before each upload, the publisher sends an HTTP HEAD request. If the artifact exists (and optionally matches the local file size), it skips the upload. This makes repeat/cron runs dramatically faster.

**Resumability:** Mirror is resumable — existing files on disk are skipped. Publishing with `skip-existing=true` (default) also skips already-uploaded artifacts.

**Concurrency:** Both scraper and publisher use Java 21 virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`, throttled by `Semaphore` to cap concurrent HTTP requests.

**Path depth constraint:** `source.root-url` and `target.root-url` must point to the same depth relative to the repository root. The scraper preserves the directory tree relative to `source.root-url`; the publisher reconstructs it relative to `target.root-url`.

## Configuration

All configuration is via Spring Boot properties / environment variables:

| Property | Env var | Default |
|---|---|---|
| `source.root-url` | `ADVISOR_MAVEN_SOURCE_URL` | *(required)* |
| `source.user` | `ADVISOR_MAVEN_SERVER_USERNAME` | none |
| `source.password` | `ADVISOR_MAVEN_SERVER_PASSWORD` | none |
| `target.root-url` | `TARGET_MAVEN_URL` | `http://localhost:8081/repository/maven-releases/` |
| `target.user` | `TARGET_MAVEN_USERNAME` | `admin` |
| `target.password` | `TARGET_MAVEN_PASSWORD` | none |
| `mirror-path` | `MIRROR_PATH` | `./mirror/` |
| `actions` | — | `mirror,publish` |
| `sync.scraper.max-concurrent` | `SYNC_SCRAPER_MAX_CONCURRENT` | `6` |
| `sync.scraper.file-delay-ms` | `SYNC_SCRAPER_FILE_DELAY_MS` | `500` |
| `sync.scraper.directory-delay-ms` | `SYNC_SCRAPER_DIRECTORY_DELAY_MS` | `1000` |
| `sync.publisher.max-concurrent` | `SYNC_PUBLISHER_MAX_CONCURRENT` | `8` |
| `sync.publisher.file-delay-ms` | `SYNC_PUBLISHER_FILE_DELAY_MS` | `1000` |
| `sync.publisher.skip-existing` | `SYNC_PUBLISHER_SKIP_EXISTING` | `true` |
| `sync.publisher.verify-size` | `SYNC_PUBLISHER_VERIFY_SIZE` | `true` |
| `sync.retry.max-attempts` | `SYNC_RETRY_MAX_ATTEMPTS` | `3` |
| `sync.retry.backoff-base-ms` | `SYNC_RETRY_BACKOFF_BASE_MS` | `1000` |

HTTP proxy is picked up automatically from the JVM's default `ProxySelector` (i.e., standard `-Dhttp.proxyHost` / `-Dhttps.proxyHost` JVM flags or system proxy settings).
