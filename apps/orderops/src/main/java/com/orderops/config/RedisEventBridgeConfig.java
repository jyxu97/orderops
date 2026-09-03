package com.orderops.config;

import com.orderops.realtime.OrderEventBroadcaster;
import com.orderops.realtime.OrderEventPublisher;
import com.orderops.realtime.RedisEventBridgeStarter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * an idle subscription, and on {@code realtime.redis-bridge.enabled} so tests can switch the
 * bridge off entirely rather than watch it retry.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "app.mode", havingValue = "api")
public class RedisEventBridgeConfig {

    // @ConditionalOnProperty is not repeatable, so the enabled flag is applied per bean rather
    // than on the class. Both beans carry it: the starter requires the container, and a @Bean
    // method whose dependency was conditioned away fails the context instead of being skipped.
    private static final String ENABLED_PROPERTY = "realtime.redis-bridge.enabled";

    @Value("${realtime.redis-bridge.retry-interval-ms:10000}")
    private long retryIntervalMs;

    @Bean
    @ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
    public RedisMessageListenerContainer orderEventListenerContainer(
        RedisConnectionFactory connectionFactory, OrderEventBroadcaster broadcaster) {

        // "onMessage" is resolved reflectively by the adapter.
        MessageListenerAdapter adapter = new MessageListenerAdapter(broadcaster, "onMessage");
        adapter.afterPropertiesSet();

        RedisMessageListenerContainer container = new LazyStartListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(adapter, new PatternTopic(OrderEventPublisher.CHANNEL));

        // Re-subscribe after a connection drop that happens once the container is listening.
        container.setRecoveryInterval(retryIntervalMs);

        return container;
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
    public RedisEventBridgeStarter redisEventBridgeStarter(RedisMessageListenerContainer container) {
        log.info("Redis event bridge configured for channel {}, starting after context refresh",
            OrderEventPublisher.CHANNEL);
        return new RedisEventBridgeStarter(container, retryIntervalMs);
    }

    /**
     * A container that the application context will not start for us.
     *
     * <p>{@code RedisMessageListenerContainer} exposes no {@code setAutoStartup}, so opting out
     * of lifecycle-driven startup means overriding {@link #isAutoStartup()}. Subscribing during
     * context refresh would make an unreachable Redis fail this bean and stop the API booting;
     * {@link RedisEventBridgeStarter} starts it afterwards instead, and retries.
     */
    private static final class LazyStartListenerContainer extends RedisMessageListenerContainer {
        @Override
        public boolean isAutoStartup() {
            return false;
        }
    }
}
