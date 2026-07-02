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
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/**
 * Starts DynamoDB Local for integration tests.
 *
 * <p>Strategy (tried in order):
 * <ol>
 *   <li>Docker CLI — {@code docker run} with {@code amazon/dynamodb-local:2.3.0}. Used when the
 *       Docker daemon socket is reachable within 1 second.</li>
 *   <li>Local JAR — downloads the DynamoDB Local tarball from the AWS CDN (cached in
 *       {@code /tmp/dynamodb-local-cache}) and starts it as a child {@code java} process. Used
 *       when Docker is not available (e.g., CI without Docker or Docker Desktop not running).</li>
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

    private static boolean isDockerAvailable() {
        String sock = System.getProperty("DOCKER_HOST",
            System.getenv("DOCKER_HOST") != null ? System.getenv("DOCKER_HOST") : "");
        // Try the standard socket path used by Docker Desktop on macOS
        String socketPath = sock.isEmpty()
            ? System.getProperty("user.home") + "/.docker/run/docker.sock"
            : sock.replace("unix://", "");
        File f = new File(socketPath);
        if (!f.exists()) return false;
        // Quick TCP-style availability check by connecting to the Unix domain socket
        try {
            Process p = new ProcessBuilder("docker", "info", "--format", "{{.ID}}")
                .redirectErrorStream(true)
                .start();
            // Give Docker 2 seconds to respond
            boolean finished = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception e) {
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
        waitUntilReady(port);
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
        waitUntilReady(port);
        createTables(client);
        return new DynamoDbLocalProcess(port, null, proc, client);
    }

    /**
     * Ensures the DynamoDB Local JAR and native libs are present in {@link #CACHE_DIR}.
     * Downloads and extracts the tarball on first call.
     */
    private static Path ensureDynamoDbLocalJar() throws IOException, InterruptedException {
        Path jar = CACHE_DIR.resolve("DynamoDBLocal.jar");
        if (Files.exists(jar)) {
            log.info("Using cached DynamoDB Local at {}", CACHE_DIR);
            return CACHE_DIR;
        }

        log.info("Downloading DynamoDB Local from {} ...", DYNAMO_LOCAL_URL);
        Files.createDirectories(CACHE_DIR);

        HttpClient http = HttpClient.newBuilder()
            .followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
            .build();
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(DYNAMO_LOCAL_URL))
            .GET().build();
        Path tarball = CACHE_DIR.resolve("dynamodb_local_latest.tar.gz");
        http.send(req, HttpResponse.BodyHandlers.ofFile(tarball));
        log.info("Download complete, extracting to {}", CACHE_DIR);

        extractTarGz(tarball, CACHE_DIR);
        Files.deleteIfExists(tarball);
        log.info("DynamoDB Local ready at {}", CACHE_DIR);
        return CACHE_DIR;
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

    private static void waitUntilReady(int port) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            try (Socket ignored = new Socket("localhost", port)) {
                log.info("DynamoDB Local is ready on port {}", port);
                return;
            } catch (IOException e) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("DynamoDB Local did not become ready on port " + port);
    }

    private static void createTables(DynamoDbClient client) {
        createTable(client, "Orders",
            List.of(attr("orderId", ScalarAttributeType.S)),
            List.of(key("orderId", KeyType.HASH)));

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
        try {
            client.createTable(CreateTableRequest.builder()
                .tableName(name)
                .attributeDefinitions(attrs)
                .keySchema(keys)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build());
        } catch (ResourceInUseException ignored) {
            // table already exists
        }
    }

    private static AttributeDefinition attr(String name, ScalarAttributeType type) {
        return AttributeDefinition.builder().attributeName(name).attributeType(type).build();
    }

    private static KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}
