package com.orderops.config;

import com.orderops.realtime.OrderEventBroadcaster;
import com.orderops.realtime.OrderEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * Subscribes this instance to the Redis order-event channel and hands each message to
 * {@link OrderEventBroadcaster}.
 *
 * <p>Only the API serves WebSocket connections, so only the API needs to receive: the worker
 * publishes but never subscribes. Gated on {@code app.mode=api} so a worker task does not hold
 * an idle subscription, and on {@code realtime.redis-bridge.enabled} so tests without a Redis
 * server can switch the eager subscription off.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.mode", havingValue = "api")
public class RedisEventBridgeConfig {

    @Bean
    @ConditionalOnProperty(name = "realtime.redis-bridge.enabled", havingValue = "true", matchIfMissing = true)
    public RedisMessageListenerContainer orderEventListenerContainer(
        RedisConnectionFactory connectionFactory, OrderEventBroadcaster broadcaster) {

        // "onMessage" is resolved reflectively by the adapter.
        MessageListenerAdapter adapter = new MessageListenerAdapter(broadcaster, "onMessage");
        adapter.afterPropertiesSet();

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(adapter, new PatternTopic(OrderEventPublisher.CHANNEL));

        log.info("Subscribed to Redis channel {} for WebSocket fan-out", OrderEventPublisher.CHANNEL);
        return container;
    }
}
