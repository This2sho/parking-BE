package com.parkingcomestrue.external.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkingcomestrue.fake.CircuitBreakerTestService2;
import com.parkingcomestrue.fake.CircuitBreakerTestService3;
import com.parkingcomestrue.fake.CircuitBreakerTestService4;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * CircuitBreaker AOP 전체 동작 테스트.
 *
 * 각 테스트는 서로 다른 서비스를 사용하거나,
 * 실행 순서를 명시적으로 지정하여 상태 의존성을 관리.
 *
 * 검증 항목:
 * 1. 에러율 20% 초과 시 서킷 열림 (요청 차단)
 * 2. 에러율 20% 미만 시 서킷 유지 (요청 허용)
 * 3. 최소 요청 횟수(10회) 미만 시 에러율 무시
 * 4. 서킷 열린 후 resetTime(200ms) 이후 자동으로 닫힘
 * 5. 서킷 열린 상태에서 요청 시 메서드 실행되지 않음
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CircuitBreakerFullTest {

    @Autowired
    private CircuitBreakerTestService2 serviceA;  // 서킷 열림 테스트용

    @Autowired
    private CircuitBreakerTestService3 serviceB;  // 복구 테스트용

    @Autowired
    private CircuitBreakerTestService4 serviceC;  // 경계값 테스트용

    // ==================== serviceA를 사용하는 테스트 (순서대로 실행) ====================

    @Test
    @Order(1)
    @DisplayName("[serviceA] 에러율이 정확히 20%일 때 서킷이 열린다 (8성공 + 2실패)")
    void test01_circuitOpensAtExactly20PercentErrorRate() {
        // given: 8번 성공 + 2번 실패 = 20% 에러율
        for (int i = 0; i < 8; i++) {
            serviceA.call(() -> {});
        }
        for (int i = 0; i < 2; i++) {
            callAndIgnoreException(serviceA);
        }

        // when: 서킷이 열린 상태에서 호출
        AtomicBoolean executed = new AtomicBoolean(false);
        serviceA.call(() -> executed.set(true));

        // then: 실행되지 않음 (서킷 열림)
        assertThat(executed.get())
                .as("서킷이 열리면 메서드가 실행되지 않아야 함")
                .isFalse();
    }

    @Test
    @Order(2)
    @DisplayName("[serviceA] 서킷 열린 상태에서 여러 번 호출해도 메서드가 실행되지 않는다")
    void test02_methodNotExecutedWhenCircuitOpen() {
        // given: Order(1) 테스트에서 서킷이 열린 상태

        // when: 여러 번 호출
        AtomicInteger executionCount = new AtomicInteger(0);
        for (int i = 0; i < 5; i++) {
            serviceA.call(() -> executionCount.incrementAndGet());
        }

        // then: 한 번도 실행되지 않음
        assertThat(executionCount.get())
                .as("서킷이 열린 상태에서는 메서드가 실행되지 않아야 함")
                .isZero();
    }

    @Test
    @Order(3)
    @DisplayName("[serviceA] 서킷 열린 상태에서 예외를 던지는 람다도 실행되지 않는다")
    void test03_noExceptionWhenCircuitOpen() {
        // given: Order(1) 테스트에서 서킷이 열린 상태

        // when & then: 예외 없이 정상 종료 (람다가 실행되지 않으므로)
        serviceA.call(() -> {
            throw new RuntimeException("이 예외는 발생하면 안 됨");
        });
        // 테스트가 여기까지 도달하면 성공
    }

    // ==================== serviceB를 사용하는 테스트 (복구 테스트) ====================

    @Test
    @Order(10)
    @DisplayName("[serviceB] 서킷 열린 후 resetTime(200ms) 이후 자동으로 닫힌다")
    void test10_circuitClosesAfterResetTime() throws InterruptedException {
        // given: 서킷 열기
        for (int i = 0; i < 8; i++) {
            serviceB.call(() -> {});
        }
        for (int i = 0; i < 2; i++) {
            callAndIgnoreException(serviceB);
        }

        // 서킷이 열렸는지 확인
        AtomicBoolean beforeReset = new AtomicBoolean(false);
        serviceB.call(() -> beforeReset.set(true));
        assertThat(beforeReset.get())
                .as("서킷이 열린 직후에는 메서드가 실행되지 않아야 함")
                .isFalse();

        // when: resetTime 대기 (200ms + 여유)
        Thread.sleep(500);

        // then: 서킷 닫혀서 실행됨
        AtomicBoolean afterReset = new AtomicBoolean(false);
        serviceB.call(() -> afterReset.set(true));
        assertThat(afterReset.get())
                .as("resetTime 이후에는 서킷이 닫혀서 메서드가 실행되어야 함")
                .isTrue();
    }

    @Test
    @Order(11)
    @DisplayName("[serviceB] 서킷 복구 후 카운터가 리셋되어 새로운 에러 카운트 시작")
    void test11_counterResetsAfterRecovery() {
        // given: Order(10) 테스트에서 서킷이 복구됨
        // 복구 후 새로운 요청들 (10% 에러율)
        for (int i = 0; i < 9; i++) {
            serviceB.call(() -> {});
        }
        callAndIgnoreException(serviceB);

        // when
        AtomicBoolean executed = new AtomicBoolean(false);
        serviceB.call(() -> executed.set(true));

        // then: 10% < 20% 이므로 서킷 닫힘
        assertThat(executed.get())
                .as("에러율이 20% 미만이면 서킷이 열리지 않아야 함")
                .isTrue();
    }

    // ==================== serviceC를 사용하는 테스트 (경계값 테스트) ====================

    @Test
    @Order(20)
    @DisplayName("[serviceC] 최소 요청 횟수(10회) 미만이면 100% 에러율이어도 서킷 열리지 않음")
    void test20_circuitStaysClosedBelowMinimumCount() {
        // given: 5번 모두 실패 (100% 에러율, but 최소 횟수 미달)
        for (int i = 0; i < 5; i++) {
            callAndIgnoreException(serviceC);
        }

        // when
        AtomicBoolean executed = new AtomicBoolean(false);
        serviceC.call(() -> executed.set(true));

        // then: 최소 횟수 미달로 서킷 열리지 않음
        assertThat(executed.get())
                .as("최소 요청 횟수 미달이면 에러율과 무관하게 서킷이 열리지 않아야 함")
                .isTrue();
    }

    @Test
    @Order(21)
    @DisplayName("[serviceC] 정확히 10번째 요청에서 에러율 계산이 시작된다")
    void test21_errorRateCalculationStartsAtTenthRequest() {
        // given: Order(20)에서 5번 실패 + 1번 성공 = 6번 호출됨
        // 3번 더 실패하면 9번 → 아직 최소 횟수 미달
        for (int i = 0; i < 3; i++) {
            callAndIgnoreException(serviceC);
        }

        // 9번째까지는 서킷 열리지 않음 (최소 10회 미달)
        AtomicBoolean beforeTenth = new AtomicBoolean(false);
        serviceC.call(() -> beforeTenth.set(true));
        assertThat(beforeTenth.get())
                .as("9회까지는 최소 횟수 미달로 서킷이 열리지 않아야 함")
                .isTrue();

        // when: 10번째 실패 (이제 최소 횟수 충족)
        // 현재 상태: 8실패 + 2성공 = 80% 에러율
        callAndIgnoreException(serviceC);

        // then: 서킷 열림
        AtomicBoolean afterTenth = new AtomicBoolean(false);
        serviceC.call(() -> afterTenth.set(true));
        assertThat(afterTenth.get())
                .as("10회 이후 에러율이 20% 이상이면 서킷이 열려야 함")
                .isFalse();
    }

    // ==================== 동시성 테스트 (독립적) ====================

    @Test
    @Order(30)
    @DisplayName("[독립] 동시에 여러 스레드에서 요청해도 에러율이 정확하게 계산된다")
    void test30_accurateErrorRateWithConcurrentRequests() throws InterruptedException {
        // serviceA의 리셋을 기다림
        Thread.sleep(500);

        // given
        int threadCount = 100;
        int successCount = 79;  // 79% 성공, 21% 실패 → 서킷 열림
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // when: 동시에 100개 요청 (79 성공, 21 실패)
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    if (index < successCount) {
                        serviceA.call(() -> {});
                    } else {
                        callAndIgnoreException(serviceA);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // then: 에러율 21%로 서킷 열림
        AtomicBoolean executed = new AtomicBoolean(false);
        serviceA.call(() -> executed.set(true));
        assertThat(executed.get())
                .as("동시 요청에서도 에러율 21%이면 서킷이 열려야 함")
                .isFalse();
    }

    // ===== Helper 메서드 =====

    private void callAndIgnoreException(CircuitBreakerTestService2 service) {
        try {
            service.call(() -> { throw new RuntimeException("의도된 실패"); });
        } catch (Exception ignored) {}
    }

    private void callAndIgnoreException(CircuitBreakerTestService3 service) {
        try {
            service.call(() -> { throw new RuntimeException("의도된 실패"); });
        } catch (Exception ignored) {}
    }

    private void callAndIgnoreException(CircuitBreakerTestService4 service) {
        try {
            service.call(() -> { throw new RuntimeException("의도된 실패"); });
        } catch (Exception ignored) {}
    }
}
