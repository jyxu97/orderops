package com.orderops.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Brings the Redis listener container up after the application is already serving traffic, and
 * keeps checking that it is actually subscribed.
 *
 * <p>Exists because subscribing during context refresh makes Redis a hard startup dependency:
 * with the container left on lifecycle-driven startup, an unreachable Redis fails the
 * {@code orderEventListenerContainer} bean and the whole API refuses to boot. That inverts the
 * project's own consistency model — order state lives in DynamoDB and real-time delivery is a
 * hint layered on top, so losing Redis should cost live updates, not the ability to take orders.
 */
@Slf4j
@RequiredArgsConstructor
public class RedisEventBridgeStarter {

    /** What was last written to the log, so a steady state is not logged on every tick. */
    private enum Phase { UNKNOWN, LISTENING, NOT_LISTENING }

    private final RedisMessageListenerContainer container;
    private final long retryIntervalMs;

    private volatile Phase logged = Phase.UNKNOWN;

    /**
     * Polls the subscription rather than trusting {@code start()} to report the truth.
     *
     * <p>{@code start()} is not a reliable signal: once the container's internal running flag is
     * set, a second call short-circuits and returns normally even though no subscription was
     * ever established, so "start() did not throw" would read as success while Redis is still
     * refusing connections. {@code isListening()} reflects whether a subscription actually
     * exists, which is the thing that matters.
     *
     * <p>Because a failed start is sticky, each retry stops the container before starting it
     * again. That also makes this a standing health check rather than just a startup helper: a
     * connection that dropped and never recovered is picked up on the next tick.
     *
     * <p>In API mode nothing else uses the scheduler; the worker's SQS poll loop is a different
     * process.
     */
    @Scheduled(
        initialDelayString = "${realtime.redis-bridge.retry-interval-ms:10000}",
        fixedDelayString = "${realtime.redis-bridge.retry-interval-ms:10000}")
    public void ensureListening() {
        if (container.isListening()) {
            transitionTo(Phase.LISTENING);
            return;
        }

        try {
            // A start() that failed to connect still leaves the container's internal running
            // flag set, and every later start() then short-circuits on it — so without an
            // explicit stop() the bridge stays dead forever once the first attempt fails, even
            // after Redis comes back. Stopping first clears that flag so the next start really
            // does try to subscribe.
            if (container.isRunning()) {
                container.stop();
            }
            container.start();
        } catch (RuntimeException e) {
            // Expected while Redis is unreachable; the phase log below carries the message.
            log.debug("Redis listener container start attempt failed: {}", e.getMessage());
        }

        // Re-check: a successful start subscribes before returning, so this reports the outcome
        // of the attempt just made rather than making the caller wait a whole interval.
        transitionTo(container.isListening() ? Phase.LISTENING : Phase.NOT_LISTENING);
    }

    /** True when a subscription is currently established. */
    public boolean isListening() {
        return container.isListening();
    }

    private void transitionTo(Phase phase) {
        if (logged == phase) {
            return;
        }
        logged = phase;

        if (phase == Phase.LISTENING) {
            log.info("Redis event bridge is listening on {}; WebSocket fan-out is live",
                OrderEventPublisher.CHANNEL);
        } else {
            log.warn("Redis event bridge is not subscribed to {}; real-time updates are degraded "
                    + "and will recover automatically. Orders are unaffected. Retrying every {}ms.",
                OrderEventPublisher.CHANNEL, retryIntervalMs);
        }
    }
}
