package com.parkingcomestrue.external.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.HttpStatus;

/**
 * RestTemplate Retry 설정 테스트.
 *
 * 검증 항목:
 * 1. 5xx 에러 (HttpServerErrorException) 시 최대 3회 재시도
 * 2. 연결 에러 (ResourceAccessException) 시 최대 3회 재시도
 * 3. 일반 예외 시 재시도하지 않음
 * 4. Exponential Backoff 적용 (1초 → 2초 → 4초)
 * 5. 최종 실패 시 예외 전파
 */
@SpringBootTest
class RetryTemplateTest {

    @Autowired
    private RetryTemplate httpRetryTemplate;

    @Nested
    @DisplayName("재시도 대상 예외 테스트")
    class RetryableExceptionTest {

        @Test
        @DisplayName("HttpServerErrorException (5xx) 발생 시 3회 재시도")
        void retryOnHttpServerErrorException() {
            // given
            AtomicInteger callCount = new AtomicInteger(0);

            // when
            try {
                httpRetryTemplate.execute(context -> {
                    callCount.incrementAndGet();
                    throw HttpServerErrorException.create(
                            HttpStatus.INTERNAL_SERVER_ERROR,
                            "Server Error",
                            null, null, null
                    );
                });
            } catch (HttpServerErrorException e) {
                // expected
            }

            // then: 최대 3회 시도
            assertThat(callCount.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("ResourceAccessException (연결 에러) 발생 시 3회 재시도")
        void retryOnResourceAccessException() {
            // given
            AtomicInteger callCount = new AtomicInteger(0);

            // when
            try {
                httpRetryTemplate.execute(context -> {
                    callCount.incrementAndGet();
                    throw new ResourceAccessException("Connection refused");
                });
            } catch (ResourceAccessException e) {
                // expected
            }

            // then: 최대 3회 시도
            assertThat(callCount.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("503 Service Unavailable 발생 시 3회 재시도")
        void retryOn503Error() {
            // given
            AtomicInteger callCount = new AtomicInteger(0);

            // when
            try {
                httpRetryTemplate.execute(context -> {
                    callCount.incrementAndGet();
                    throw HttpServerErrorException.create(
                            HttpStatus.SERVICE_UNAVAILABLE,
                            "Service Unavailable",
                            null, null, null
                    );
                });
            } catch (HttpServerErrorException e) {
                // expected
            }

            // then
            assertThat(callCount.get()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("재시도 불가 예외 테스트")
    class NonRetryableExceptionTest {

        @Test
        @DisplayName("IllegalArgumentException 발생 시 재시도하지 않음")
        void noRetryOnIllegalArgumentException() {
            // given
            AtomicInteger callCount = new AtomicInteger(0);

            // when
            try {
                httpRetryTemplate.execute(context -> {
                    callCount.incrementAndGet();
                    throw new IllegalArgumentException("Invalid argument");
                });
            } catch (IllegalArgumentException e) {
                // expected
            }

            // then: 1회만 시도 (재시도 안 함)
            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("RuntimeException 발생 시 재시도하지 않음")
        void noRetryOnRuntimeException() {
            // given
            AtomicInteger callCount = new AtomicInteger(0);

            // when
            try {
                httpRetryTemplate.execute(context -> {
                    callCount.incrementAndGet();
                    throw new RuntimeException("General error");
                });
            } catch (RuntimeException e) {
                // expected
            }

            // then: 1회만 시도
            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("NullPointerException 발생 시 재시도하지 않음")
        void noRetryOnNullPointerException() {
            // given
            AtomicInteger callCount = new AtomicInteger(0);

            // when
            try {
                httpRetryTemplate.execute(context -> {
                    callCount.incrementAndGet();
                    throw new NullPointerException("Null value");
                });
            } catch (NullPointerException e) {
                // expected
            }

            // then: 1회만 시도
            assertThat(callCount.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("재시도 성공 시나리오 테스트")
    class RetrySuccessTest {

        @Test
        @DisplayName("2번 실패 후 3번째 성공하면 결과 반환")
        void returnResultOnThirdAttemptSuccess() {
            // given
            AtomicInteger callCount = new AtomicInteger(0);

            // when
            String result = httpRetryTemplate.execute(context -> {
                if (callCount.incrementAndGet() < 3) {
                    throw new ResourceAccessException("Connection refused");
                }
                return "success";
            });

            // then
            assertThat(result).isEqualTo("success");
            assertThat(callCount.get()).isEqualTo(3);
        }

        @Test
        @DisplayName("1번 실패 후 2번째 성공하면 결과 반환")
        void returnResultOnSecondAttemptSuccess() {
            // given
            AtomicInteger callCount = new AtomicInteger(0);

            // when
            String result = httpRetryTemplate.execute(context -> {
                if (callCount.incrementAndGet() < 2) {
                    throw new ResourceAccessException("Connection refused");
                }
                return "success on second";
            });

            // then
            assertThat(result).isEqualTo("success on second");
            assertThat(callCount.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("첫 번째 시도에서 성공하면 재시도 없이 결과 반환")
        void noRetryOnFirstAttemptSuccess() {
            // given
            AtomicInteger callCount = new AtomicInteger(0);

            // when
            String result = httpRetryTemplate.execute(context -> {
                callCount.incrementAndGet();
                return "immediate success";
            });

            // then
            assertThat(result).isEqualTo("immediate success");
            assertThat(callCount.get()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Backoff 동작 테스트")
    class BackoffTest {

        @Test
        @DisplayName("재시도 시 Exponential Backoff 적용되어 총 지연 시간이 3초 이상")
        void exponentialBackoffApplied() {
            // given
            AtomicInteger callCount = new AtomicInteger(0);
            List<Long> timestamps = new ArrayList<>();

            long startTime = System.currentTimeMillis();

            // when: 3회 재시도 (첫 시도 → 1초 후 재시도 → 2초 후 재시도)
            try {
                httpRetryTemplate.execute(context -> {
                    timestamps.add(System.currentTimeMillis());
                    callCount.incrementAndGet();
                    throw new ResourceAccessException("Connection refused");
                });
            } catch (ResourceAccessException e) {
                // expected
            }

            long totalElapsed = System.currentTimeMillis() - startTime;

            // then
            assertThat(callCount.get()).isEqualTo(3);

            // 첫 시도 → 1초 대기 → 두 번째 시도 → 2초 대기 → 세 번째 시도
            // 총 최소 3초 (1초 + 2초) 지연
            assertThat(totalElapsed).isGreaterThanOrEqualTo(2500);  // 약간의 여유

            // 각 시도 간 간격 확인
            if (timestamps.size() >= 2) {
                long firstToSecond = timestamps.get(1) - timestamps.get(0);
                assertThat(firstToSecond).isGreaterThanOrEqualTo(900);  // 약 1초
            }
            if (timestamps.size() >= 3) {
                long secondToThird = timestamps.get(2) - timestamps.get(1);
                assertThat(secondToThird).isGreaterThanOrEqualTo(1800);  // 약 2초
            }
        }

        @Test
        @DisplayName("재시도 횟수에 따라 대기 시간이 증가한다")
        void backoffIntervalIncreases() {
            // given
            List<Long> intervals = new ArrayList<>();
            AtomicInteger callCount = new AtomicInteger(0);
            long[] lastCallTime = {System.currentTimeMillis()};

            // when
            try {
                httpRetryTemplate.execute(context -> {
                    long currentTime = System.currentTimeMillis();
                    if (callCount.get() > 0) {
                        intervals.add(currentTime - lastCallTime[0]);
                    }
                    lastCallTime[0] = currentTime;
                    callCount.incrementAndGet();
                    throw new ResourceAccessException("Connection refused");
                });
            } catch (ResourceAccessException e) {
                // expected
            }

            // then: 두 번째 간격이 첫 번째 간격보다 길어야 함
            assertThat(intervals).hasSize(2);
            assertThat(intervals.get(1)).isGreaterThan(intervals.get(0));
        }
    }

    @Nested
    @DisplayName("최종 실패 시 예외 전파 테스트")
    class FinalFailureTest {

        @Test
        @DisplayName("3회 모두 실패하면 마지막 예외가 전파된다")
        void propagatesLastExceptionAfterMaxRetries() {
            // given
            AtomicInteger callCount = new AtomicInteger(0);

            // when & then
            assertThatThrownBy(() ->
                    httpRetryTemplate.execute(context -> {
                        callCount.incrementAndGet();
                        throw new ResourceAccessException("Connection refused - attempt " + callCount.get());
                    })
            )
                    .isInstanceOf(ResourceAccessException.class)
                    .hasMessageContaining("attempt 3");

            assertThat(callCount.get()).isEqualTo(3);
        }
    }
}
