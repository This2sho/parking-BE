package com.parkingcomestrue.external.api;

import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * API 호출 횟수 및 에러율을 추적하는 카운터.
 * 서킷브레이커 패턴에서 에러율 기반 차단 결정에 사용됨.
 *
 * <p>동시성 고려사항:
 * - totalCount, errorCount, isOpened를 하나의 immutable state로 묶어 CAS로 갱신
 * - count 증가와 open 판정을 하나의 상태 전이로 처리
 */
@Slf4j
public class ApiCounter {

    private final int minTotalCount;
    private final AtomicReference<State> state;

    public ApiCounter() {
        this(10);
    }

    public ApiCounter(int minTotalCount) {
        this.minTotalCount = minTotalCount;
        this.state = new AtomicReference<>(new State(0, 0, false));
    }

    public void totalCountUp() {
        updateState(1, 0, Double.POSITIVE_INFINITY);
    }

    public void errorCountUp() {
        updateState(1, 1, Double.POSITIVE_INFINITY);
    }

    public boolean recordSuccess(double errorRate) {
        return updateState(1, 0, errorRate);
    }

    public boolean recordFailure(double errorRate) {
        return updateState(1, 1, errorRate);
    }

    public void reset() {
        state.set(new State(0, 0, false));
        log.info("ApiCounter reset - circuit closed. totalCount={}, errorCount={}",
                getTotalCount(), getErrorCount());
    }

    public boolean isOpened() {
        return state.get().opened();
    }

    public void open() {
        while (true) {
            State current = state.get();
            if (current.opened()) {
                return;
            }
            State next = new State(current.totalCount(), current.errorCount(), true);
            if (state.compareAndSet(current, next)) {
                log.warn("ApiCounter opened - circuit open. totalCount={}, errorCount={}",
                        next.totalCount(), next.errorCount());
                return;
            }
        }
    }

    /**
     * 에러율이 임계치를 초과하는지 확인.
     * 최소 요청 횟수(minTotalCount)를 넘어야 판단.
     *
     * @param errorRate 에러율 임계치 (0.0 ~ 1.0)
     * @return 임계치 초과 여부
     */
    public boolean isErrorRateOverThan(double errorRate) {
        State current = state.get();
        return shouldOpen(current.totalCount(), current.errorCount(), errorRate);
    }

    public int getTotalCount() {
        return state.get().totalCount();
    }

    public int getErrorCount() {
        return state.get().errorCount();
    }

    private boolean updateState(int totalDelta, int errorDelta, double errorRate) {
        while (true) {
            State current = state.get();
            int nextTotal = current.totalCount() + totalDelta;
            int nextError = current.errorCount() + errorDelta;
            boolean nextOpened = current.opened() || shouldOpen(nextTotal, nextError, errorRate);
            State next = new State(nextTotal, nextError, nextOpened);
            if (state.compareAndSet(current, next)) {
                if (!current.opened() && next.opened()) {
                    log.warn("ApiCounter opened - circuit open. totalCount={}, errorCount={}",
                            next.totalCount(), next.errorCount());
                    return true;
                }
                return false;
            }
        }
    }

    private boolean shouldOpen(int totalCount, int errorCount, double errorRate) {
        if (totalCount < minTotalCount) {
            return false;
        }
        return ((double) errorCount / totalCount) >= errorRate;
    }

    private record State(int totalCount, int errorCount, boolean opened) {
    }
}
