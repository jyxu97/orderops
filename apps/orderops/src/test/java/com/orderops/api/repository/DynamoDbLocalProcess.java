package com.orderops.api.repository;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.io.*;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Starts DynamoDB Local for integration tests.
 *
 * <p>Strategy (tried in order):
 * <ol>
 *   <li>Docker CLI — {@code docker run} with {@code amazon/dynamodb-local:2.3.0}. Preferred
 *       whenever {@code docker info} succeeds, which includes CI runners; it needs no network
 *       access beyond the image pull.</li>
 *   <li>Local JAR — downloads the DynamoDB Local tarball from the AWS CDN (cached in
 *       {@code /tmp/dynamodb-local-cache}) and starts it as a child {@code java} process. Only
 *       for machines with no usable Docker, because it makes the test phase depend on an
 *       external CDN.</li>
 * </ol>
 *
 * <p>This avoids the Testcontainers docker-java library which is incompatible with Docker Desktop
 * 29.x on macOS (the versioned {@code /info} endpoint returns HTTP 400).
 */
@Slf4j
public final class DynamoDbLocalProcess implements AutoCloseable {

    private static final String DOCKER_IMAGE = "amazon/dynamodb-local:2.3.0";
    private static final String DYNAMO_LOCAL_URL =
        "https://d1ni2b6xgvw0s0.cloudfront.net/v2.x/dynamodb_local_latest.tar.gz";
    private static final Path CACHE_DIR = Path.of(System.getProperty("java.io.tmpdir"), "dynamodb-local-cache");
    /** Attempts before giving up on the CDN. Transient handshake failures are the common case. */
    private static final int DOWNLOAD_ATTEMPTS = 3;

    private final int port;
    /** Container ID (Docker mode) or {@code null} (JAR mode). */
    private final String containerId;
    /** Child process (JAR mode) or {@code null} (Docker mode). */
    private final Process jarProcess;
    private final DynamoDbClient client;

    private DynamoDbLocalProcess(int port, String containerId, Process jarProcess, DynamoDbClient client) {
        this.port = port;
        this.containerId = containerId;
        this.jarProcess = jarProcess;
        this.client = client;
    }

    public DynamoDbClient client() { return client; }
    public int port()              { return port; }
    public String endpoint()       { return "http://localhost:" + port; }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static DynamoDbLocalProcess start() throws IOException, InterruptedException {
        int port = findFreePort();
        if (isDockerAvailable()) {
            return startViaDocker(port);
        } else {
            log.info("Docker not available — starting DynamoDB Local from JAR");
            return startViaJar(port);
        }
    }

    // -------------------------------------------------------------------------
    // Docker mode
    // -------------------------------------------------------------------------

    /**
     * Whether the Docker CLI can reach a working daemon.
     *
     * <p>Decided solely by {@code docker info}'s exit code. An earlier version first required
     * a socket file at {@code ~/.docker/run/docker.sock} — the Docker Desktop path on macOS —
     * and returned false when it was absent. On a Linux CI runner the socket lives at
     * {@code /var/run/docker.sock}, so that guard rejected a perfectly good daemon and sent
     * every run down the JAR-download fallback, which then depended on an external CDN being
     * reachable during the test phase. Asking the CLI is both simpler and correct everywhere,
     * including remote and rootless contexts where no local socket path exists at all.
     */
    private static boolean isDockerAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "info", "--format", "{{.ID}}")
                .redirectErrorStream(true)
                .start();
            // A cold daemon can take a few seconds to answer; 2s was tight enough to be its
            // own source of false negatives.
            boolean finished = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            // No docker binary on PATH, or it could not be executed.
            return false;
        }
    }

    private static DynamoDbLocalProcess startViaDocker(int port) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            "docker", "run", "--rm", "-d",
            "-p", port + ":8000",
            DOCKER_IMAGE,
            "-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory"
        );
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String containerId = new String(proc.getInputStream().readAllBytes()).trim();
        int exitCode = proc.waitFor();
        if (exitCode != 0 || containerId.isBlank()) {
            throw new IllegalStateException("docker run failed; exit=" + exitCode + " output=" + containerId);
        }
        log.info("Started DynamoDB Local container id={} port={}", containerId.substring(0, 12), port);

        DynamoDbClient client = buildClient(port);
        waitUntilReady(client, port);
        createTables(client);
        return new DynamoDbLocalProcess(port, containerId, null, client);
    }

    // -------------------------------------------------------------------------
    // JAR mode
    // -------------------------------------------------------------------------

    private static DynamoDbLocalProcess startViaJar(int port) throws IOException, InterruptedException {
        Path jarDir = ensureDynamoDbLocalJar();
        Path jar    = jarDir.resolve("DynamoDBLocal.jar");
        Path libs   = jarDir.resolve("DynamoDBLocal_lib");

        String javaHome = ProcessHandle.current().info().command()
            .map(cmd -> cmd.replace("/bin/java", ""))
            .orElse(System.getProperty("java.home"));

        ProcessBuilder pb = new ProcessBuilder(
            javaHome + "/bin/java",
            "-Djava.library.path=" + libs.toAbsolutePath(),
            "-jar", jar.toAbsolutePath().toString(),
            "-sharedDb", "-inMemory",
            "-port", String.valueOf(port)
        );
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process proc = pb.start();
        log.info("Started DynamoDB Local JAR process pid={} port={}", proc.pid(), port);

        DynamoDbClient client = buildClient(port);
        waitUntilReady(client, port);
        createTables(client);
        return new DynamoDbLocalProcess(port, null, proc, client);
    }

    /**
     * Ensures the DynamoDB Local JAR and native libs are present in {@link #CACHE_DIR}.
     * Downloads and extracts the tarball on first call.
     *
     * <p>This is the fallback for machines with no Docker, and it reaches out to an external
     * CDN during the test phase — so it is written to survive that CDN misbehaving. Three
     * things matter:
     *
     * <ul>
     *   <li><b>Serialized.</b> Six test classes start their own instance from a static
     *       initializer. Without the lock, two of them can both see an empty cache and
     *       download concurrently, which is how a real CI run ended up with one of the two
     *       handshakes terminated by the CDN.</li>
     *   <li><b>Staged.</b> The tarball is fetched to a temporary file and expanded into a
     *       temporary directory, then moved into place. A half-extracted cache directory can
     *       never be mistaken for a usable one by a later call.</li>
     *   <li><b>Retried.</b> A terminated handshake or a truncated body is transient, and
     *       failing the whole suite over it wastes a run.</li>
     * </ul>
     */
    private static synchronized Path ensureDynamoDbLocalJar() throws IOException, InterruptedException {
        Path jar = CACHE_DIR.resolve("DynamoDBLocal.jar");
        if (Files.exists(jar)) {
            log.info("Using cached DynamoDB Local at {}", CACHE_DIR);
            return CACHE_DIR;
        }

        IOException lastFailure = null;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            Path staging = Files.createTempDirectory("dynamodb-local-staging");
            try {
                log.info("Downloading DynamoDB Local from {} (attempt {}/{}) ...",
                    DYNAMO_LOCAL_URL, attempt, DOWNLOAD_ATTEMPTS);

                HttpClient http = HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();
                HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(DYNAMO_LOCAL_URL))
                    .timeout(Duration.ofMinutes(3))
                    .GET().build();

                Path tarball = staging.resolve("dynamodb_local_latest.tar.gz");
                HttpResponse<Path> response = http.send(req, HttpResponse.BodyHandlers.ofFile(tarball));
                if (response.statusCode() != 200) {
                    throw new IOException("CDN returned HTTP " + response.statusCode());
                }

                Path expanded = staging.resolve("expanded");
                Files.createDirectories(expanded);
                extractTarGz(tarball, expanded);
                if (!Files.exists(expanded.resolve("DynamoDBLocal.jar"))) {
                    throw new IOException("archive did not contain DynamoDBLocal.jar");
                }

                // Publish only once the contents are known good. ATOMIC_MOVE is best-effort:
                // it fails across filesystems, so fall back to a plain move.
                Files.createDirectories(CACHE_DIR.getParent());
                try {
                    Files.move(expanded, CACHE_DIR, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnsupported) {
                    Files.move(expanded, CACHE_DIR, StandardCopyOption.REPLACE_EXISTING);
                }

                log.info("DynamoDB Local ready at {}", CACHE_DIR);
                return CACHE_DIR;

            } catch (IOException e) {
                lastFailure = e;
                log.warn("DynamoDB Local download failed (attempt {}/{}): {}",
                    attempt, DOWNLOAD_ATTEMPTS, e.toString());
                if (attempt < DOWNLOAD_ATTEMPTS) {
                    Thread.sleep(2000L * attempt);
                }
            } finally {
                deleteRecursively(staging);
            }
        }

        throw new IOException(
            "Could not obtain DynamoDB Local after " + DOWNLOAD_ATTEMPTS + " attempt(s). "
                + "Start Docker to avoid the download entirely.", lastFailure);
    }

    /** Best-effort cleanup of a staging directory; failure to tidy up must not fail a test. */
    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // nothing useful to do
                }
            });
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    private static void extractTarGz(Path tarball, Path destDir) throws IOException {
        try (InputStream fis = Files.newInputStream(tarball);
             GZIPInputStream gis = new GZIPInputStream(fis);
             TarArchiveInputStream tar = new TarArchiveInputStream(gis)) {

            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) continue;
                Path target = destDir.resolve(entry.getName()).normalize();
                if (!target.startsWith(destDir)) {
                    throw new IOException("Bad zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING);
                    // Preserve executable bit for native libs
                    if ((entry.getMode() & 0100) != 0) {
                        target.toFile().setExecutable(true, false);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    @Override
    public void close() {
        if (containerId != null) {
            try {
                new ProcessBuilder("docker", "stop", containerId)
                    .redirectErrorStream(true).start().waitFor();
                log.info("Stopped DynamoDB Local container id={}", containerId.substring(0, 12));
            } catch (Exception e) {
                log.warn("Failed to stop container {}", containerId, e);
            }
        }
        if (jarProcess != null) {
            jarProcess.destroyForcibly();
            log.info("Stopped DynamoDB Local JAR process pid={}", jarProcess.pid());
        }
    }

    private static DynamoDbClient buildClient(int port) {
        return DynamoDbClient.builder()
            .region(Region.US_WEST_2)
            .endpointOverride(URI.create("http://localhost:" + port))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("local", "local")))
            .httpClient(UrlConnectionHttpClient.builder().build())
            .build();
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    /**
     * Blocks until DynamoDB Local answers a real API call.
     *
     * <p>Probing with {@code ListTables} rather than a bare socket connect matters: the port
     * starts accepting connections before the server can serve HTTP, and a request issued in
     * that window fails with "Connection reset".
     */
    private static void waitUntilReady(DynamoDbClient client, int port) throws InterruptedException {
        RuntimeException lastFailure = null;
        for (int i = 0; i < 120; i++) {
            try {
                client.listTables(ListTablesRequest.builder().limit(1).build());
                log.info("DynamoDB Local is ready on port {}", port);
                return;
            } catch (RuntimeException e) {
                lastFailure = e;
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException(
            "DynamoDB Local did not become ready on port " + port, lastFailure);
    }

    private static void createTables(DynamoDbClient client) {
        // Mirrors infra/dynamodb/create-tables.sh — keep the two in sync.
        createTable(client, "Orders",
            List.of(attr("orderId",    ScalarAttributeType.S),
                    attr("customerId", ScalarAttributeType.S),
                    attr("status",     ScalarAttributeType.S),
                    attr("createdAt",  ScalarAttributeType.S),
                    attr("updatedAt",  ScalarAttributeType.S)),
            List.of(key("orderId", KeyType.HASH)),
            List.of(gsi("GSI1_CustomerCreatedAt", "customerId", "createdAt"),
                    gsi("GSI2_StatusUpdatedAt",   "status",     "updatedAt")));

        createTable(client, "Inventory",
            List.of(attr("itemId", ScalarAttributeType.S)),
            List.of(key("itemId", KeyType.HASH)));

        createTable(client, "IdempotencyRecords",
            List.of(attr("idempotencyKey", ScalarAttributeType.S)),
            List.of(key("idempotencyKey", KeyType.HASH)));

        createTable(client, "OrderAuditLogs",
            List.of(attr("orderId", ScalarAttributeType.S), attr("timestamp", ScalarAttributeType.S)),
            List.of(key("orderId", KeyType.HASH), key("timestamp", KeyType.RANGE)));
    }

    private static void createTable(DynamoDbClient client, String name,
                                    List<AttributeDefinition> attrs,
                                    List<KeySchemaElement> keys) {
        createTable(client, name, attrs, keys, List.of());
    }

    private static void createTable(DynamoDbClient client, String name,
                                    List<AttributeDefinition> attrs,
                                    List<KeySchemaElement> keys,
                                    List<GlobalSecondaryIndex> indexes) {
        try {
            var request = CreateTableRequest.builder()
                .tableName(name)
                .attributeDefinitions(attrs)
                .keySchema(keys)
                .billingMode(BillingMode.PAY_PER_REQUEST);
            if (!indexes.isEmpty()) {
                request.globalSecondaryIndexes(indexes);
            }
            client.createTable(request.build());
        } catch (ResourceInUseException ignored) {
            // table already exists
        }
    }

    private static GlobalSecondaryIndex gsi(String indexName, String hashKey, String rangeKey) {
        return GlobalSecondaryIndex.builder()
            .indexName(indexName)
            .keySchema(key(hashKey, KeyType.HASH), key(rangeKey, KeyType.RANGE))
            .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
            .build();
    }

    private static AttributeDefinition attr(String name, ScalarAttributeType type) {
        return AttributeDefinition.builder().attributeName(name).attributeType(type).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}
