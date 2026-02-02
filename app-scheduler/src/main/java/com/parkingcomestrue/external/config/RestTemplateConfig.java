package com.parkingcomestrue.external.config;

import com.parkingcomestrue.external.api.coordinate.CoordinateErrorHandler;
import java.time.Duration;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * RestTemplate 설정.
 *
 * <p>재시도 정책:
 * - 최대 3회 재시도
 * - 재시도 대상 예외: 5xx 서버 에러, 연결/타임아웃 에러
 * - Exponential Backoff: 1초 → 2초 → 4초 (최대 10초)
 *
 * <p>4xx 에러는 재시도하지 않음 (클라이언트 요청 문제)
 */
@Slf4j
@Configuration
public class RestTemplateConfig {

    private static final String AUTH_HEADER = "Authorization";
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long INITIAL_BACKOFF_MS = 1000L;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final long MAX_BACKOFF_MS = 10000L;

    /**
     * 재시도 가능한 예외 목록.
     * - HttpServerErrorException: 5xx 서버 에러
     * - ResourceAccessException: 연결 실패, 타임아웃 등
     */
    @Bean
    public RetryTemplate httpRetryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        // 재시도 대상 예외 설정 (5xx, 연결/타임아웃만 재시도)
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = Map.of(
                HttpServerErrorException.class, true,      // 5xx 서버 에러
                ResourceAccessException.class, true        // 연결 실패, 타임아웃
        );
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(MAX_RETRY_ATTEMPTS, retryableExceptions);
        retryTemplate.setRetryPolicy(retryPolicy);

        // Exponential Backoff 설정 (1초 → 2초 → 4초)
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(INITIAL_BACKOFF_MS);
        backOffPolicy.setMultiplier(BACKOFF_MULTIPLIER);
        backOffPolicy.setMaxInterval(MAX_BACKOFF_MS);
        retryTemplate.setBackOffPolicy(backOffPolicy);

        // 재시도 리스너 (로깅)
        retryTemplate.registerListener(new RetryLoggingListener());

        return retryTemplate;
    }

    @Bean
    @Qualifier("coordinateRestTemplate")
    public RestTemplate coordinateRestTemplate(RestTemplateBuilder restTemplateBuilder,
                                               @Value("${kakao.key}") String kakaoUrl,
                                               RetryTemplate httpRetryTemplate) {
        return restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(5))
                .errorHandler(new CoordinateErrorHandler())
                .defaultHeader(AUTH_HEADER, kakaoUrl)
                .additionalInterceptors(retryInterceptor(httpRetryTemplate))
                .build();
    }

    @Bean
    @Qualifier("parkingApiRestTemplate")
    public RestTemplate parkingApiRestTemplate(RestTemplateBuilder restTemplateBuilder,
                                               RetryTemplate httpRetryTemplate) {
        return restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(60))
                .errorHandler(new ParkingApiErrorHandler())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_UTF8_VALUE)
                .additionalInterceptors(retryInterceptor(httpRetryTemplate))
                .build();
    }

    /**
     * HTTP 요청에 재시도 로직을 적용하는 인터셉터.
     * RetryTemplate을 Bean으로 주입받아 재사용.
     */
    private ClientHttpRequestInterceptor retryInterceptor(RetryTemplate retryTemplate) {
        return (request, body, execution) -> {
            try {
                return retryTemplate.execute(context -> {
                    if (context.getRetryCount() > 0) {
                        log.info("HTTP 요청 재시도 중... attempt={}, uri={}",
                                context.getRetryCount() + 1, request.getURI());
                    }
                    return execution.execute(request, body);
                });
            } catch (Exception e) {
                log.error("HTTP 요청 최종 실패. uri={}, error={}", request.getURI(), e.getMessage());
                throw e;
            }
        };
    }

    /**
     * 재시도 이벤트 로깅 리스너
     */
    private static class RetryLoggingListener implements RetryListener {

        @Override
        public <T, E extends Throwable> void onError(
                RetryContext context,
                RetryCallback<T, E> callback,
                Throwable throwable) {
            log.warn("재시도 필요한 에러 발생. attempt={}, error={}",
                    context.getRetryCount(), throwable.getMessage());
        }
    }
}
