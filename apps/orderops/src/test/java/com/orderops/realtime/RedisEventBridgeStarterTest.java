package com.orderops.realtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression cover for the two ways this bridge previously broke:
 * an unreachable Redis stopping the API from booting, and a failed first connect leaving the
 * bridge permanently dead even after Redis returned.
 */
class RedisEventBridgeStarterTest {

    private RedisMessageListenerContainer container;
    private RedisEventBridgeStarter starter;

    @BeforeEach
    void setUp() {
        container = Mockito.mock(RedisMessageListenerContainer.class);
        starter = new RedisEventBridgeStarter(container, 4_000L);
    }

    @Test
    void redisUnreachable_doesNotPropagate() {
        Mockito.when(container.isListening()).thenReturn(false);
        Mockito.doThrow(new RedisConnectionFailureException("Unable to connect to Redis"))
            .when(container).start();

        // This runs on the scheduler, but the same throw during context refresh is what used
        // to stop the whole API from starting. It must stay swallowed.
        assertDoesNotThrow(() -> starter.ensureListening());
        assertFalse(starter.isListening());
    }

    @Test
    void successfulSubscribe_reportsListening() {
        Mockito.when(container.isListening()).thenReturn(true);

        starter.ensureListening();

        assertTrue(starter.isListening());
    }

    @Test
    void retryAfterFailedStart_stopsBeforeStartingAgain() {
        // A start() that failed to connect leaves the container's running flag set, and every
        // later start() then short-circuits on it. Without the stop() the bridge would stay
        // dead forever, which is exactly the bug this asserts against.
        Mockito.when(container.isListening()).thenReturn(false);
        Mockito.when(container.isRunning()).thenReturn(true);

        starter.ensureListening();

        InOrder inOrder = Mockito.inOrder(container);
        inOrder.verify(container).stop();
        inOrder.verify(container).start();
    }

    @Test
    void firstAttempt_doesNotStopAContainerThatWasNeverRunning() {
        Mockito.when(container.isListening()).thenReturn(false);
        Mockito.when(container.isRunning()).thenReturn(false);

        starter.ensureListening();

        Mockito.verify(container, Mockito.never()).stop();
        Mockito.verify(container).start();
    }

    @Test
    void recoversOnceRedisComesBack() {
        // Modelled as state rather than a call sequence: the starter checks isListening() more
        // than once per tick, so counting stubbed returns would assert on its internals.
        AtomicBoolean redisReachable = new AtomicBoolean(false);
        AtomicBoolean subscribed = new AtomicBoolean(false);

        Mockito.when(container.isListening()).thenAnswer(invocation -> subscribed.get());
        Mockito.when(container.isRunning()).thenAnswer(invocation -> true);
        Mockito.doAnswer(invocation -> {
            if (redisReachable.get()) {
                subscribed.set(true);
            } else {
                throw new RedisConnectionFailureException("Unable to connect to Redis");
            }
            return null;
        }).when(container).start();

        starter.ensureListening();
        assertFalse(starter.isListening(), "still down after the first tick");

        starter.ensureListening();
        assertFalse(starter.isListening(), "still down after the second tick");

        redisReachable.set(true);
        starter.ensureListening();

        assertTrue(starter.isListening(), "bridge must recover without an application restart");
    }

    @Test
    void alreadyListening_isNotRestarted() {
        Mockito.when(container.isListening()).thenReturn(true);

        starter.ensureListening();
        starter.ensureListening();

        // Restarting a healthy subscription would drop events for no reason.
        Mockito.verify(container, Mockito.never()).start();
        Mockito.verify(container, Mockito.never()).stop();
    }
}
