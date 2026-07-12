package org.jeecg.modules.airag.practice.aspect.breaker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCircuitBreakerTest {

    @Test
    void halfOpenAllowsOnlyOneProbeAtATime() throws InterruptedException {
        LocalCircuitBreaker breaker = new LocalCircuitBreaker("test", 2, 10);
        breaker.onFailure();
        breaker.onFailure();
        assertEquals(LocalCircuitBreaker.State.OPEN, breaker.getState());

        Thread.sleep(20);
        assertTrue(breaker.allowRequest());
        assertFalse(breaker.allowRequest());

        breaker.onSuccess();
        assertTrue(breaker.allowRequest());
    }

    @Test
    void threeSequentialHalfOpenProbesCloseBreaker() throws InterruptedException {
        LocalCircuitBreaker breaker = new LocalCircuitBreaker("test", 1, 10);
        breaker.onFailure();
        Thread.sleep(20);

        for (int i = 0; i < 3; i++) {
            assertTrue(breaker.allowRequest());
            breaker.onSuccess();
        }

        assertEquals(LocalCircuitBreaker.State.CLOSED, breaker.getState());
        assertTrue(breaker.allowRequest());
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new LocalCircuitBreaker("test", 0, 1000));
    }
}
