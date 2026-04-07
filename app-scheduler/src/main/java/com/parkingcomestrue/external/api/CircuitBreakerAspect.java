package com.parkingcomestrue.external.api;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class CircuitBreakerAspect {

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);
    private final Map<Object, ApiCounter> map = new ConcurrentHashMap<>();

    @Around("@annotation(annotation)")
    public Object around(ProceedingJoinPoint proceedingJoinPoint, CircuitBreaker annotation) {
        ApiCounter apiCounter = getApiCounter(proceedingJoinPoint, annotation.minTotalCount());
        if (apiCounter.isOpened()) {
            log.warn("현재 해당 {} API는 오류로 인해 중지되었습니다.", proceedingJoinPoint.getTarget());
            return null;
        }
        try {
            Object result = proceedingJoinPoint.proceed();
            recordSuccess(annotation, apiCounter);
            return result;
        } catch (Throwable e) {
            recordFailure(annotation, apiCounter);
            return null;
        }
    }

    private ApiCounter getApiCounter(ProceedingJoinPoint proceedingJoinPoint, int minTotalCount) {
        Object target = proceedingJoinPoint.getTarget();
        return map.computeIfAbsent(target, key -> new ApiCounter(minTotalCount));
    }

    private void recordSuccess(CircuitBreaker annotation, ApiCounter apiCounter) {
        if (apiCounter.recordSuccess(annotation.errorRate())) {
            scheduler.schedule(apiCounter::reset, annotation.resetTime(), annotation.timeUnit());
        }
    }

    private void recordFailure(CircuitBreaker annotation, ApiCounter apiCounter) {
        if (apiCounter.recordFailure(annotation.errorRate())) {
            scheduler.schedule(apiCounter::reset, annotation.resetTime(), annotation.timeUnit());
        }
    }
}
