package com.parkingcomestrue.fake;

import com.parkingcomestrue.external.api.CircuitBreaker;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class CircuitBreakerTestService4 {

    @CircuitBreaker(resetTime = 200, timeUnit = TimeUnit.MILLISECONDS)
    public void call(Runnable runnable) {
        runnable.run();
    }
}
