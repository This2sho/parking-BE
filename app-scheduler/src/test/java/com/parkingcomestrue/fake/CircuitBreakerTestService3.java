package com.parkingcomestrue.fake;

import com.parkingcomestrue.external.api.CircuitBreaker;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * CircuitBreaker 테스트용 서비스 3 (상태 격리용)
 */
@Component
public class CircuitBreakerTestService3 {

    @CircuitBreaker(resetTime = 200, timeUnit = TimeUnit.MILLISECONDS)
    public void call(Runnable runnable) {
        runnable.run();
    }
}
