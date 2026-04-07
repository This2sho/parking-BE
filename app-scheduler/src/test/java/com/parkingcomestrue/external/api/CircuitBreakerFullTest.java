package com.parkingcomestrue.external.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.parkingcomestrue.fake.CircuitBreakerTestService2;
import com.parkingcomestrue.fake.CircuitBreakerTestService3;
import com.parkingcomestrue.fake.CircuitBreakerTestService4;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CircuitBreakerFullTest {

    @Autowired
    private CircuitBreakerTestService2 serviceA;

    @Autowired
    private CircuitBreakerTestService3 serviceB;

    @Autowired
    private CircuitBreakerTestService4 serviceC;

    @Test
    @DisplayName("실패 요청이 임계치에 도달하면 다음 요청부터 차단된다")
    void opensCircuitWhenFailureRequestReachesThreshold() {
        for (int i = 0; i < 8; i++) {
            serviceA.call(() -> {});
        }

        AtomicInteger failureExecutionCount = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            serviceA.call(() -> failWithCount(failureExecutionCount));
        }

        AtomicBoolean executedAfterOpen = new AtomicBoolean(false);
        serviceA.call(() -> executedAfterOpen.set(true));

        assertThat(failureExecutionCount.get()).isEqualTo(2);
        assertThat(executedAfterOpen.get()).isFalse();
    }

    @Test
    @DisplayName("최소 요청 수에 못 미치면 높은 실패율이어도 차단되지 않는다")
    void staysClosedBelowMinimumRequestCount() {
        AtomicInteger failureExecutionCount = new AtomicInteger();
        for (int i = 0; i < 5; i++) {
            serviceA.call(() -> failWithCount(failureExecutionCount));
        }

        AtomicBoolean executed = new AtomicBoolean(false);
        serviceA.call(() -> executed.set(true));

        assertThat(failureExecutionCount.get()).isEqualTo(5);
        assertThat(executed.get()).isTrue();
    }

    @Test
    @DisplayName("차단 직전까지는 요청이 실행되고 차단 이후 요청만 막힌다")
    void allowsRequestBeforeOpeningAndBlocksNextRequest() {
        AtomicInteger failureExecutionCount = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            serviceA.call(() -> failWithCount(failureExecutionCount));
        }

        AtomicBoolean tenthRequestExecuted = new AtomicBoolean(false);
        serviceA.call(() -> tenthRequestExecuted.set(true));

        AtomicBoolean eleventhFailureExecuted = new AtomicBoolean(false);
        serviceA.call(() -> {
            eleventhFailureExecuted.set(true);
            throw new RuntimeException("threshold crossing failure");
        });

        AtomicBoolean blockedAfterOpen = new AtomicBoolean(false);
        serviceA.call(() -> blockedAfterOpen.set(true));

        assertThat(tenthRequestExecuted.get()).isTrue();
        assertThat(eleventhFailureExecuted.get()).isTrue();
        assertThat(blockedAfterOpen.get()).isFalse();
    }

    @Test
    @DisplayName("열린 서킷은 reset time 이후 다시 닫히고 요청을 허용한다")
    void closesAfterResetTime() {
        openCircuit(serviceB);

        AtomicBoolean blockedWhileOpen = new AtomicBoolean(false);
        serviceB.call(() -> blockedWhileOpen.set(true));
        assertThat(blockedWhileOpen.get()).isFalse();

        AtomicBoolean executedAfterReset = new AtomicBoolean(false);
        await()
                .atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(25))
                .untilAsserted(() -> {
                    executedAfterReset.set(false);
                    serviceB.call(() -> executedAfterReset.set(true));
                    assertThat(executedAfterReset.get()).isTrue();
                });
    }

    @Test
    @DisplayName("reset 이후에는 이전 실패율이 아닌 새 카운터로 다시 계산한다")
    void resetsCountersAfterRecovery() {
        openCircuit(serviceB);

        await()
                .atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(25))
                .untilAsserted(() -> {
                    AtomicBoolean probe = new AtomicBoolean(false);
                    serviceB.call(() -> probe.set(true));
                    assertThat(probe.get()).isTrue();
                });

        for (int i = 0; i < 9; i++) {
            serviceB.call(() -> {});
        }
        serviceB.call(() -> {
            throw new RuntimeException("single failure after reset");
        });

        AtomicBoolean executed = new AtomicBoolean(false);
        serviceB.call(() -> executed.set(true));

        assertThat(executed.get()).isTrue();
    }

    @Test
    @DisplayName("한 서비스의 서킷이 열려도 다른 서비스 요청은 영향을 받지 않는다")
    void isolatesCircuitStatePerTargetBean() {
        openCircuit(serviceA);

        AtomicBoolean blockedOnServiceA = new AtomicBoolean(false);
        serviceA.call(() -> blockedOnServiceA.set(true));

        AtomicBoolean executedOnServiceB = new AtomicBoolean(false);
        serviceB.call(() -> executedOnServiceB.set(true));

        assertThat(blockedOnServiceA.get()).isFalse();
        assertThat(executedOnServiceB.get()).isTrue();
    }

    @Test
    @DisplayName("동시 요청에서도 임계 실패율에 도달하면 이후 요청은 차단된다")
    void opensCircuitAfterConcurrentRequestsReachThreshold() throws InterruptedException {
        int totalRequests = 10;
        int successRequests = 8;
        ExecutorService executor = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch ready = new CountDownLatch(totalRequests);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(totalRequests);
        AtomicInteger executedSuccessCount = new AtomicInteger();
        AtomicInteger executedFailureCount = new AtomicInteger();

        for (int i = 0; i < totalRequests; i++) {
            final boolean shouldFail = i >= successRequests;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    if (shouldFail) {
                        serviceC.call(() -> failWithCount(executedFailureCount));
                    } else {
                        serviceC.call(executedSuccessCount::incrementAndGet);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

        AtomicBoolean blockedAfterConcurrentCalls = new AtomicBoolean(false);
        serviceC.call(() -> blockedAfterConcurrentCalls.set(true));

        assertThat(executedSuccessCount.get()).isEqualTo(successRequests);
        assertThat(executedFailureCount.get()).isEqualTo(totalRequests - successRequests);
        assertThat(blockedAfterConcurrentCalls.get()).isFalse();
    }

    private void openCircuit(CircuitBreakerTestService2 service) {
        for (int i = 0; i < 8; i++) {
            service.call(() -> {});
        }
        for (int i = 0; i < 2; i++) {
            service.call(() -> {
                throw new RuntimeException("open circuit");
            });
        }
    }

    private void openCircuit(CircuitBreakerTestService3 service) {
        for (int i = 0; i < 8; i++) {
            service.call(() -> {});
        }
        for (int i = 0; i < 2; i++) {
            service.call(() -> {
                throw new RuntimeException("open circuit");
            });
        }
    }

    private void failWithCount(AtomicInteger counter) {
        counter.incrementAndGet();
        throw new RuntimeException("intentional failure");
    }
}
