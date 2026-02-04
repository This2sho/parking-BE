package com.parkingcomestrue.external.scheduler;


import com.parkingcomestrue.external.api.coordinate.CoordinateApiService;
import com.parkingcomestrue.fake.ExceptionParkingApiService;
import com.parkingcomestrue.fake.FakeCoordinateApiService;
import com.parkingcomestrue.fake.FakeParkingBatchRepository;
import com.parkingcomestrue.fake.NotOfferCurrentParkingApiService;
import com.parkingcomestrue.fake.OfferCurrentParkingApiService;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ParkingUpdateSchedulerTest {

    private final FakeParkingBatchRepository parkingRepository = new FakeParkingBatchRepository();
    private final CoordinateApiService coordinateService = new FakeCoordinateApiService();
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

    @DisplayName("실시간 주차 대수를 제공하는 API에서 주차장이 0~4까지 저장되어 있는 상태에서 0~9까지 주차장을 읽어와 업데이트한다.")
    @Test
    void autoUpdateOfferCurrentParking() {
        //given
        OfferCurrentParkingApiService offerCurrentParkingApiService = new OfferCurrentParkingApiService(5);
        parkingRepository.saveAll(offerCurrentParkingApiService.read(0, offerCurrentParkingApiService.getReadSize()));
        int readSize = 10;
        offerCurrentParkingApiService.setReadSize(readSize);

        ParkingUpdateScheduler scheduler = new ParkingUpdateScheduler(
                List.of(offerCurrentParkingApiService),
                coordinateService,
                parkingRepository,
                executor
        );

        //when
        scheduler.autoUpdateOfferCurrentParking();

        //then
        Assertions.assertThat(parkingRepository.count()).isEqualTo(readSize);
    }

    @DisplayName("실시간 주차 대수를 제공하지 않는 API에서 주차장이 0~4까지 저장되어 있는 상태에서 0~9까지 주차장을 읽어와 업데이트한다.")
    @Test
    void autoUpdateNotOfferCurrentParking() {
        //given
        NotOfferCurrentParkingApiService notOfferCurrentParkingApiService = new NotOfferCurrentParkingApiService(
                5);
        parkingRepository.saveAll(notOfferCurrentParkingApiService.read(0, notOfferCurrentParkingApiService.getReadSize()));
        int readSize = 10;
        notOfferCurrentParkingApiService.setReadSize(readSize);

        ParkingUpdateScheduler scheduler = new ParkingUpdateScheduler(
                List.of(notOfferCurrentParkingApiService),
                coordinateService,
                parkingRepository,
                executor
        );

        //when
        scheduler.autoUpdateNotOfferCurrentParking();

        //then
        Assertions.assertThat(parkingRepository.count()).isEqualTo(readSize);
    }

    @DisplayName("실시간 주차 대수를 제공하는 API와 제공하지 않는 API는 영향을 안준다.")
    @Test
    void notAffectBetweenOfferAndNotOfferCurrentParking() {
        //given
        OfferCurrentParkingApiService offerCurrentParkingApiService = new OfferCurrentParkingApiService(5);
        NotOfferCurrentParkingApiService notOfferCurrentParkingApiService = new NotOfferCurrentParkingApiService(
                5);
        parkingRepository.saveAll(offerCurrentParkingApiService.read(0, offerCurrentParkingApiService.getReadSize()));
        int readSize = 10;
        notOfferCurrentParkingApiService.setReadSize(readSize);

        ParkingUpdateScheduler scheduler = new ParkingUpdateScheduler(
                List.of(offerCurrentParkingApiService, notOfferCurrentParkingApiService),
                coordinateService,
                parkingRepository,
                executor
        );

        //when
        scheduler.autoUpdateOfferCurrentParking();

        //then
        Assertions.assertThat(parkingRepository.count()).isEqualTo(5);
    }

    @DisplayName("특정 API에서 예외 발생시, 해당 API는 log를 남기고 무시한다.")
    @Test
    void autoUpdateWithExceptionApi() {
        //given
        ParkingUpdateScheduler scheduler = new ParkingUpdateScheduler(
                List.of(new OfferCurrentParkingApiService(5), new ExceptionParkingApiService()),
                coordinateService,
                parkingRepository,
                executor
        );

        //when
        scheduler.autoUpdateOfferCurrentParking();

        //then
        Assertions.assertThat(parkingRepository.count()).isEqualTo(5);
    }
}
