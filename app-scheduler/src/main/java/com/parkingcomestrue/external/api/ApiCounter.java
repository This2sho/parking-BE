package com.parkingcomestrue.external.api;

import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * API 호출 횟수 및 에러율을 추적하는 카운터.
 * 서킷브레이커 패턴에서 에러율 기반 차단 결정에 사용됨.
 *
 * <p>동시성 고려사항:
 * - totalCount, errorCount: AtomicInteger로 CAS 기반 스레드 안전 보장
 * - isOpened: volatile로 가시성 보장
 * - reset(): set(0)을 사용하여 참조 교체 문제 방지
 */
@Slf4j
public class ApiCounter {

    private final int MIN_TOTAL_COUNT;

    private final AtomicInteger totalCount;
    private final AtomicInteger errorCount;
    private volatile boolean isOpened;

    public ApiCounter() {
        this(10);
    }

    public ApiCounter(int minTotalCount) {
        this.MIN_TOTAL_COUNT = minTotalCount;
        this.totalCount = new AtomicInteger(0);
        this.errorCount = new AtomicInteger(0);
        this.isOpened = false;
    }

    public void totalCountUp() {
        totalCount.incrementAndGet();
    }

    public void errorCountUp() {
        totalCountUp();
        errorCount.incrementAndGet();
    }

    /**
     * 카운터를 초기화하고 서킷을 닫음.
     * AtomicInteger.set(0)을 사용하여 참조 교체 없이 안전하게 리셋.
     */
    public void reset() {
        totalCount.set(0);
        errorCount.set(0);
        isOpened = false;
        log.info("ApiCounter reset - circuit closed. totalCount={}, errorCount={}",
                totalCount.get(), errorCount.get());
    }

    public boolean isOpened() {
        return isOpened;
    }

    public void open() {
        isOpened = true;
        log.warn("ApiCounter opened - circuit open. totalCount={}, errorCount={}",
                totalCount.get(), errorCount.get());
    }

    /**
     * 에러율이 임계치를 초과하는지 확인.
     * 최소 요청 횟수(MIN_TOTAL_COUNT)를 넘어야 판단.
     *
     * @param errorRate 에러율 임계치 (0.0 ~ 1.0)
     * @return 임계치 초과 여부
     */
    public boolean isErrorRateOverThan(double errorRate) {
        int currentTotalCount = getTotalCount();
        int currentErrorCount = getErrorCount();
        if (currentTotalCount < MIN_TOTAL_COUNT) {
            return false;
        }
        double currentErrorRate = (double) currentErrorCount / currentTotalCount;
        return currentErrorRate >= errorRate;
    }

    public int getTotalCount() {
        return totalCount.get();
    }

    public int getErrorCount() {
        return errorCount.get();
    }
}
