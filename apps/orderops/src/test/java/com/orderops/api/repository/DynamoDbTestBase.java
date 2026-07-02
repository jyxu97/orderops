package com.orderops.api.repository;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Base class for repository integration tests.
 *
 * <p>Starts a DynamoDB Local container via the {@code docker} CLI (bypassing
 * Testcontainers docker-java which is incompatible with Docker Desktop 29.x on macOS).
 * The container is started once per class load and stopped via a JVM shutdown hook.
 */
public abstract class DynamoDbTestBase {

    protected static final DynamoDbClient dynamoDb;

    static {
        try {
            DynamoDbLocalProcess process = DynamoDbLocalProcess.start();
            dynamoDb = process.client();
            Runtime.getRuntime().addShutdownHook(new Thread(process::close));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
