package com.orderops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.net.URI;

@Configuration
public class DynamoDbConfig {

    @Value("${aws.region:us-west-2}")
    private String region;

    @Value("${dynamodb.endpoint:}")
    private String dynamoDbEndpoint;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        var builder = DynamoDbClient.builder()
            .region(Region.of(region))
            .httpClient(UrlConnectionHttpClient.builder().build());

        if (dynamoDbEndpoint != null && !dynamoDbEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(dynamoDbEndpoint))
                   .credentialsProvider(StaticCredentialsProvider.create(
                       AwsBasicCredentials.create("local", "local")));
        }

        return builder.build();
    }
}
