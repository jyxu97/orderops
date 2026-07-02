package com.orderops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

@Configuration
public class SqsConfig {

    @Value("${aws.region:us-west-2}")
    private String region;

    @Value("${sqs.endpoint:}")
    private String sqsEndpoint;

    @Bean
    public SqsClient sqsClient() {
        var builder = SqsClient.builder()
            .region(Region.of(region))
            .httpClient(UrlConnectionHttpClient.builder().build());

        if (sqsEndpoint != null && !sqsEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(sqsEndpoint))
                   .credentialsProvider(StaticCredentialsProvider.create(
                       AwsBasicCredentials.create("local", "local")));
        }

        return builder.build();
    }
}