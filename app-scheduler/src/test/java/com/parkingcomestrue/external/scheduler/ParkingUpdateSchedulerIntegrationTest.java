package com.parkingcomestrue.external.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkingcomestrue.common.domain.parking.BaseInformation;
import com.parkingcomestrue.common.domain.parking.Fee;
import com.parkingcomestrue.common.domain.parking.FeePolicy;
import com.parkingcomestrue.common.domain.parking.FreeOperatingTime;
import com.parkingcomestrue.common.domain.parking.Location;
import com.parkingcomestrue.common.domain.parking.OperatingTime;
import com.parkingcomestrue.common.domain.parking.OperationType;
import com.parkingcomestrue.common.domain.parking.Parking;
import com.parkingcomestrue.common.domain.parking.ParkingType;
import com.parkingcomestrue.common.domain.parking.PayType;
import com.parkingcomestrue.common.domain.parking.Space;
import com.parkingcomestrue.common.domain.parking.TimeUnit;
import com.parkingcomestrue.external.api.HealthCheckResponse;
import com.parkingcomestrue.external.api.parkingapi.ParkingApiService;
import com.parkingcomestrue.fake.FakeCoordinateApiService;
import com.parkingcomestrue.fake.FakeParkingBatchRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * ParkingUpdateScheduler의 주요 통합 시나리오 테스트
 * - 모든 요청 성공
 * - 모든 요청 실패
 * - 초반 실패 → 후반 성공
 * - 초반 성공 → 후반 실패
 * - 여러 API 중 일부만 성공
 */
class ParkingUpdateSchedulerIntegrationTest {

    private FakeParkingBatchRepository parkingRepository;
    private FakeCoordinateApiService coordinateService;
    private ThreadPoolTaskExecutor executor;
    
    @BeforeEach
    void setUp() {
        parkingRepository = new FakeParkingBatchRepository();
        coordinateService = new FakeCoordinateApiService();
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(100);
        executor.setQueueCapacity(0);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60 * 5);
        executor.initialize();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("readBy() 시나리오 테스트")
    class ReadByScenarioTest {

        @Test
        @DisplayName("시나리오 1: 모든 요청이 실패해도 예외 없이 종료되고 저장은 발생하지 않는다")
        void allFailuresAreGracefullyHandled() {
            // given
            AlwaysFailParkingApiService failService = new AlwaysFailParkingApiService();
            ParkingUpdateScheduler scheduler = new ParkingUpdateScheduler(
                    List.of(failService),
                    coordinateService,
                    parkingRepository,
                    executor
            );

            // when
            scheduler.autoUpdateOfferCurrentParking();

            // then
            assertThat(parkingRepository.count()).isZero();
            assertThat(failService.getCallCount()).isGreaterThan(0);
        }

        @Test
        @DisplayName("시나리오 2: 모든 요청이 성공하면 데이터가 저장된다")
        void allRequestsSucceed() {
            // given
            int expectedSize = 10;
            AlwaysSuccessParkingApiService successService = new AlwaysSuccessParkingApiService(expectedSize);
            ParkingUpdateScheduler scheduler = new ParkingUpdateScheduler(
                    List.of(successService),
                    coordinateService,
                    parkingRepository,
                    executor
            );

            // when
            scheduler.autoUpdateOfferCurrentParking();

            // then
            assertThat(parkingRepository.count()).isEqualTo(expectedSize);
            assertThat(successService.getCallCount()).isGreaterThan(0);
            assertStoredParkingNames(expectedParkingNames("success", expectedSize));
        }

        @Test
        @DisplayName("시나리오 3: 초반 페이지 실패 후 후반 페이지가 성공하면 성공한 페이지 데이터만 저장된다")
        void earlyFailureLateSuccess() {
            // given: 5페이지 중 2페이지 실패
            int totalPages = 5;
            int failingPages = 2;
            LeadingPageFailureParkingApiService service =
                    new LeadingPageFailureParkingApiService(totalPages, failingPages);
            ParkingUpdateScheduler scheduler = new ParkingUpdateScheduler(
                    List.of(service),
                    coordinateService,
                    parkingRepository,
                    executor
            );

            // when
            scheduler.autoUpdateOfferCurrentParking();

            // then
            // 성공한 페이지(3개)의 데이터만 저장됨
            int expectedSize = totalPages - failingPages;
            assertThat(parkingRepository.count()).isEqualTo(expectedSize);
            assertThat(service.getCallCount()).isEqualTo(totalPages);
            assertStoredParkingNames(Set.of("page3_parking_0", "page4_parking_0", "page5_parking_0"));
        }

        @Test
        @DisplayName("시나리오 4: 초반 페이지 성공 후 후반 페이지가 실패해도 앞서 수집한 데이터는 저장된다")
        void earlySuccessLateFailure() {
            // given: 5페이지 중 뒤 2페이지 실패
            int totalPages = 5;
            int failingPagesFromEnd = 2;
            TrailingPageFailureParkingApiService service =
                    new TrailingPageFailureParkingApiService(totalPages, failingPagesFromEnd);
            ParkingUpdateScheduler scheduler = new ParkingUpdateScheduler(
                    List.of(service),
                    coordinateService,
                    parkingRepository,
                    executor
            );

            // when
            scheduler.autoUpdateOfferCurrentParking();

            // then
            int expectedSize = totalPages - failingPagesFromEnd;
            assertThat(parkingRepository.count()).isEqualTo(expectedSize);
            assertThat(service.getCallCount()).isEqualTo(totalPages);
            assertStoredParkingNames(Set.of("page1_parking_0", "page2_parking_0", "page3_parking_0"));
        }

        @Test
        @DisplayName("여러 API 중 일부만 성공해도 성공한 API의 데이터는 저장된다")
        void mixedApiSuccess() {
            // given: 하나는 성공, 하나는 실패
            AlwaysSuccessParkingApiService successService = new AlwaysSuccessParkingApiService(5);
            AlwaysFailParkingApiService failService = new AlwaysFailParkingApiService();
            ParkingUpdateScheduler scheduler = new ParkingUpdateScheduler(
                    List.of(successService, failService),
                    coordinateService,
                    parkingRepository,
                    executor
            );

            // when
            scheduler.autoUpdateOfferCurrentParking();

            // then: 성공한 API의 데이터만 저장됨
            assertThat(parkingRepository.count()).isEqualTo(5);
            assertThat(successService.getCallCount()).isGreaterThan(0);
            assertThat(failService.getCallCount()).isGreaterThan(0);
            assertStoredParkingNames(expectedParkingNames("success", 5));
        }
    }

    @Nested
    @DisplayName("HealthCheck 시나리오 테스트")
    class HealthCheckScenarioTest {

        @Test
        @DisplayName("HealthCheck 실패 시 read() 메서드가 호출되지 않는다")
        void healthCheckFailSkipsRead() {
            // given
            UnhealthyParkingApiService unhealthyService = new UnhealthyParkingApiService();
            ParkingUpdateScheduler scheduler = new ParkingUpdateScheduler(
                    List.of(unhealthyService),
                    coordinateService,
                    parkingRepository,
                    executor
            );

            // when
            scheduler.autoUpdateOfferCurrentParking();

            // then
            assertThat(unhealthyService.getReadCallCount()).isZero();
            assertThat(parkingRepository.count()).isZero();
        }

        @Test
        @DisplayName("여러 API 중 일부만 healthy하면 healthy한 API만 호출된다")
        void onlyHealthyApisAreCalled() {
            // given
            AlwaysSuccessParkingApiService healthyService = new AlwaysSuccessParkingApiService(5);
            UnhealthyParkingApiService unhealthyService = new UnhealthyParkingApiService();
            ParkingUpdateScheduler scheduler = new ParkingUpdateScheduler(
                    List.of(healthyService, unhealthyService),
                    coordinateService,
                    parkingRepository,
                    executor
            );

            // when
            scheduler.autoUpdateOfferCurrentParking();

            // then
            assertThat(healthyService.getCallCount()).isGreaterThan(0);
            assertThat(unhealthyService.getReadCallCount()).isZero();
            assertThat(parkingRepository.count()).isEqualTo(5);
        }
    }

    // ===== Fake ParkingApiService 구현체들 =====

    /**
     * 항상 실패하는 API 서비스
     */
    static class AlwaysFailParkingApiService implements ParkingApiService {
        private final AtomicInteger callCount = new AtomicInteger(0);

        @Override
        public boolean offerCurrentParking() {
            return true;
        }

        @Override
        public List<Parking> read(int pageNumber, int size) {
            callCount.incrementAndGet();
            throw new RuntimeException("API 호출 실패");
        }

        @Override
        public int getReadSize() {
            return 10;
        }

        @Override
        public HealthCheckResponse check() {
            return new HealthCheckResponse(true, 10);
        }

        public int getCallCount() {
            return callCount.get();
        }
    }

    /**
     * 항상 성공하는 API 서비스
     */
    static class AlwaysSuccessParkingApiService implements ParkingApiService {
        private final int dataSize;
        private final AtomicInteger callCount = new AtomicInteger(0);

        AlwaysSuccessParkingApiService(int dataSize) {
            this.dataSize = dataSize;
        }

        @Override
        public boolean offerCurrentParking() {
            return true;
        }

        @Override
        public List<Parking> read(int pageNumber, int size) {
            callCount.incrementAndGet();
            if (pageNumber > 1) {
                return Collections.emptyList();
            }
            return createParkingList(dataSize, "success");
        }

        @Override
        public int getReadSize() {
            return dataSize;
        }

        @Override
        public HealthCheckResponse check() {
            return new HealthCheckResponse(true, dataSize);
        }

        public int getCallCount() {
            return callCount.get();
        }
    }

    /**
     * 일부 페이지만 실패하는 API 서비스
     */
    static class LeadingPageFailureParkingApiService implements ParkingApiService {
        private final int totalPages;
        private final int failingPages;  // 실패할 페이지 수 (앞에서부터)
        private final AtomicInteger callCount = new AtomicInteger(0);

        LeadingPageFailureParkingApiService(int totalPages, int failingPages) {
            this.totalPages = totalPages;
            this.failingPages = failingPages;
        }

        @Override
        public boolean offerCurrentParking() {
            return true;
        }

        @Override
        public List<Parking> read(int pageNumber, int size) {
            callCount.incrementAndGet();
            // 앞쪽 페이지들은 실패
            if (pageNumber <= failingPages) {
                throw new RuntimeException("의도된 실패: 페이지 " + pageNumber);
            }
            // 나머지 페이지는 성공
            return createParkingList(1, "page" + pageNumber);
        }

        @Override
        public int getReadSize() {
            return 1;
        }

        @Override
        public HealthCheckResponse check() {
            return new HealthCheckResponse(true, totalPages);
        }

        public int getCallCount() {
            return callCount.get();
        }
    }

    /**
     * 뒤쪽 페이지만 실패하는 API 서비스
     */
    static class TrailingPageFailureParkingApiService implements ParkingApiService {
        private final int totalPages;
        private final int failingPagesFromEnd;
        private final AtomicInteger callCount = new AtomicInteger(0);

        TrailingPageFailureParkingApiService(int totalPages, int failingPagesFromEnd) {
            this.totalPages = totalPages;
            this.failingPagesFromEnd = failingPagesFromEnd;
        }

        @Override
        public boolean offerCurrentParking() {
            return true;
        }

        @Override
        public List<Parking> read(int pageNumber, int size) {
            callCount.incrementAndGet();
            if (pageNumber > totalPages - failingPagesFromEnd) {
                throw new RuntimeException("의도된 실패: 페이지 " + pageNumber);
            }
            return createParkingList(1, "page" + pageNumber);
        }

        @Override
        public int getReadSize() {
            return 1;
        }

        @Override
        public HealthCheckResponse check() {
            return new HealthCheckResponse(true, totalPages);
        }

        public int getCallCount() {
            return callCount.get();
        }
    }

    /**
     * HealthCheck가 실패하는 API 서비스
     */
    static class UnhealthyParkingApiService implements ParkingApiService {
        private final AtomicInteger readCallCount = new AtomicInteger(0);

        @Override
        public boolean offerCurrentParking() {
            return true;
        }

        @Override
        public List<Parking> read(int pageNumber, int size) {
            readCallCount.incrementAndGet();
            return Collections.emptyList();
        }

        @Override
        public int getReadSize() {
            return 10;
        }

        @Override
        public HealthCheckResponse check() {
            return new HealthCheckResponse(false, 0);
        }

        public int getReadCallCount() {
            return readCallCount.get();
        }
    }

    // ===== Helper 메서드 =====

    private static List<Parking> createParkingList(int count, String prefix) {
        List<Parking> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Parking parking = new Parking(
                    new BaseInformation(
                            prefix + "_parking_" + i,
                            "02-0000-000" + i,
                            "서울시 테스트구 " + i,
                            Set.of(PayType.NO_INFO),
                            ParkingType.NO_INFO,
                            OperationType.PUBLIC
                    ),
                    Location.of("37.5" + i, "127.0" + i),
                    Space.of(100, 50),
                    FreeOperatingTime.ALWAYS_FREE,
                    OperatingTime.ALWAYS_OPEN,
                    new FeePolicy(Fee.ZERO, Fee.ZERO, TimeUnit.from(0), TimeUnit.from(0), Fee.ZERO)
            );
            result.add(parking);
        }
        return result;
    }

    private Set<String> expectedParkingNames(String prefix, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> prefix + "_parking_" + i)
                .collect(java.util.stream.Collectors.toSet());
    }

    private void assertStoredParkingNames(Set<String> expectedNames) {
        Set<String> actualNames = parkingRepository.findAllByBaseInformationNameIn(expectedNames)
                .stream()
                .map(parking -> parking.getBaseInformation().getName())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(actualNames).containsExactlyInAnyOrderElementsOf(expectedNames);
    }
}
