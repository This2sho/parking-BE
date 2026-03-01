package com.parkingcomestrue.external.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ParkingUpdateSchedulerChunkTest {

    private final FakeCoordinateApiService coordinateService = new FakeCoordinateApiService();
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

    @BeforeEach
    void setUp() {
        executor.setCorePoolSize(20);
        executor.setMaxPoolSize(100);
        executor.setQueueCapacity(0);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60 * 5);
        executor.initialize();
    }

    @Test
    @DisplayName("청크 저장 실패 시 failedChunks에 보관되었다가 재시도하여 최종적으로 저장된다")
    void failedChunkIsRetriedAndSaved() {
        // given: 첫 번째 saveWithBatch 실패, 이후 성공
        ControllableSaveParkingBatchRepository repo = new ControllableSaveParkingBatchRepository(1);
        ParkingApiService api = new NamedParkingApiService("api", 5);

        ParkingUpdateScheduler scheduler = new ParkingUpdateScheduler(
                List.of(api), coordinateService, repo, executor);

        // when
        scheduler.autoUpdateOfferCurrentParking();

        // then: 재시도 후 저장됨
        assertThat(repo.count()).isEqualTo(5);
        assertThat(repo.getSaveCallCount()).isEqualTo(2); // 1번 실패 + 1번 재시도 성공
    }

    @Test
    @DisplayName("청크 재시도도 실패하면 해당 데이터는 누락되지만 예외 없이 종료된다")
    void failedChunkRetryAlsoFailsGracefully() {
        // given: 모든 saveWithBatch 실패
        ControllableSaveParkingBatchRepository repo = new ControllableSaveParkingBatchRepository(Integer.MAX_VALUE);
        ParkingApiService api = new NamedParkingApiService("api", 5);

        ParkingUpdateScheduler scheduler = new ParkingUpdateScheduler(
                List.of(api), coordinateService, repo, executor);

        // when & then: 예외 없이 종료
        assertThatCode(() -> scheduler.autoUpdateOfferCurrentParking()).doesNotThrowAnyException();
        assertThat(repo.count()).isZero();
    }

    @Test
    @DisplayName("여러 API 중 하나의 청크가 실패해도 다른 API 데이터는 정상 저장되고 실패 청크는 재시도된다")
    void otherApiDataSavedWhenOneChunkFails() {
        // given: 첫 번째 saveWithBatch 실패 → api1 청크 실패
        //        두 번째 이후 성공 → api2 청크 성공, api1 재시도 성공
        ControllableSaveParkingBatchRepository repo = new ControllableSaveParkingBatchRepository(1);
        ParkingApiService api1 = new NamedParkingApiService("api1", 5);
        ParkingApiService api2 = new NamedParkingApiService("api2", 3);

        ParkingUpdateScheduler scheduler = new ParkingUpdateScheduler(
                List.of(api1, api2), coordinateService, repo, executor);

        // when
        scheduler.autoUpdateOfferCurrentParking();

        // then: api1(5개) 재시도 성공 + api2(3개) 정상 저장 = 총 8개
        assertThat(repo.count()).isEqualTo(8);
        assertThat(repo.getSaveCallCount()).isEqualTo(3); // api1 실패 + api2 성공 + api1 재시도 성공
    }

    // ===== Fake 구현체 =====

    /**
     * N번째 호출까지 saveWithBatch를 실패시키는 저장소
     */
    static class ControllableSaveParkingBatchRepository extends FakeParkingBatchRepository {

        private final int failTimes;
        private int saveCallCount = 0;

        ControllableSaveParkingBatchRepository(int failTimes) {
            this.failTimes = failTimes;
        }

        @Override
        public void saveWithBatch(List<Parking> parkingLots) {
            saveCallCount++;
            if (saveCallCount <= failTimes) {
                throw new RuntimeException("의도된 저장 실패. 시도=" + saveCallCount);
            }
            super.saveWithBatch(parkingLots);
        }

        public int getSaveCallCount() {
            return saveCallCount;
        }
    }

    /**
     * 이름 prefix로 구분 가능한 주차장 데이터를 제공하는 API 서비스
     */
    static class NamedParkingApiService implements ParkingApiService {

        private final String prefix;
        private final int dataSize;

        NamedParkingApiService(String prefix, int dataSize) {
            this.prefix = prefix;
            this.dataSize = dataSize;
        }

        @Override
        public boolean offerCurrentParking() {
            return true;
        }

        @Override
        public List<Parking> read(int pageNumber, int size) {
            List<Parking> result = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                result.add(createParking(prefix + "_parking_" + i));
            }
            return result;
        }

        @Override
        public int getReadSize() {
            return dataSize;
        }

        @Override
        public HealthCheckResponse check() {
            return new HealthCheckResponse(true, dataSize);
        }

        private Parking createParking(String name) {
            return new Parking(
                    new BaseInformation(name, "02-0000-0000", "서울시 테스트구",
                            Set.of(PayType.NO_INFO), ParkingType.NO_INFO, OperationType.PUBLIC),
                    Location.of("37.5", "127.0"),
                    Space.of(100, 50),
                    FreeOperatingTime.ALWAYS_FREE,
                    OperatingTime.ALWAYS_OPEN,
                    new FeePolicy(Fee.ZERO, Fee.ZERO, TimeUnit.from(0), TimeUnit.from(0), Fee.ZERO)
            );
        }
    }
}
