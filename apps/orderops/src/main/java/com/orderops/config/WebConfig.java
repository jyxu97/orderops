package com.orderops.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the React client, which is served from a different origin in every
 * environment (Vite dev server locally, CloudFront in AWS).
 *
 * <p>Origins are configured explicitly rather than with a wildcard so that credentialed
 * requests remain possible and an arbitrary site cannot call the API from a browser.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigins)
            .allowedMethods("GET", "POST", "PATCH", "OPTIONS")
            .allowedHeaders("Content-Type", "Idempotency-Key")
            .maxAge(3600);
    }
}
