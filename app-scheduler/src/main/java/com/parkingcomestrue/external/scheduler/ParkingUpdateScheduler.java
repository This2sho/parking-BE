package com.parkingcomestrue.external.scheduler;

import com.parkingcomestrue.common.domain.parking.Location;
import com.parkingcomestrue.common.domain.parking.Parking;
import com.parkingcomestrue.external.api.coordinate.CoordinateApiService;
import com.parkingcomestrue.external.api.HealthCheckResponse;
import com.parkingcomestrue.external.api.parkingapi.ParkingApiService;
import com.parkingcomestrue.external.respository.ParkingBatchRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class ParkingUpdateScheduler {

    private final List<ParkingApiService> parkingApiServices;
    private final CoordinateApiService coordinateApiService;
    private final ParkingBatchRepository parkingBatchRepository;
    private final ExecutorService executorService;

    @Scheduled(cron = "0 */30 * * * *")
    public void autoUpdateOfferCurrentParking() {
        Map<String, Parking> parkingLots = readBy(ParkingApiService::offerCurrentParking);
        Map<String, Parking> saved = findAllByName(parkingLots.keySet());
        updateSavedParkingLots(parkingLots, saved);
        saveNewParkingLots(parkingLots, saved);
    }

    private Map<String, Parking> readBy(Predicate<ParkingApiService> currentParkingAvailable) {
        List<ParkingApiService> parkingApis = filterBy(currentParkingAvailable);
        Map<String, Parking> result = new HashMap<>();
        for (ParkingApiService parkingApi : parkingApis) {
            HealthCheckResponse healthCheckResponse = parkingApi.check();
            if (healthCheckResponse.isHealthy()) {
                List<CompletableFuture<List<Parking>>> responses = fetchParkingDataAsync(
                        parkingApi, healthCheckResponse.getTotalSize());
                Map<String, Parking> response = collectParkingData(responses);
                result.putAll(response);
            }
        }
        return result;
    }

    private List<ParkingApiService> filterBy(Predicate<ParkingApiService> currentParkingAvailable) {
        return parkingApiServices.stream()
                .filter(currentParkingAvailable)
                .toList();
    }

    private List<CompletableFuture<List<Parking>>> fetchParkingDataAsync(ParkingApiService parkingApi, int totalSize) {
        int readSize = parkingApi.getReadSize();
        int lastPageNumber = calculateLastPageNumber(totalSize, readSize);

        return Stream.iterate(1, i -> i <= lastPageNumber, i -> i + 1)
                .map(i -> CompletableFuture
                        .supplyAsync(() -> parkingApi.read(i, readSize), executorService)
                        .exceptionally(throwable -> {
                            log.error("페이지 {} 데이터 fetch 실패. API={}, error={}",
                                    i, parkingApi.getClass().getSimpleName(), throwable.getMessage());
                            return List.of();  // 실패한 페이지는 빈 리스트 반환
                        }))
                .toList();
    }

    private int calculateLastPageNumber(int totalSize, int readSize) {
        int lastPageNumber = totalSize / readSize;
        if (totalSize % readSize == 0) {
            return lastPageNumber;
        }
        return lastPageNumber + 1;
    }

    private Map<String, Parking> collectParkingData(List<CompletableFuture<List<Parking>>> responses) {
        List<List<Parking>> parkingLots = responses.stream()
                .map(CompletableFuture::join)
                .toList();

        return parkingLots.stream()
                .flatMap(Collection::stream)
                .collect(toParkingMap());
    }

    private Collector<Parking, ?, Map<String, Parking>> toParkingMap() {
        return Collectors.toMap(
                parking -> parking.getBaseInformation().getName(),
                Function.identity(),
                (existing, replacement) -> existing
        );
    }

    private Map<String, Parking> findAllByName(Set<String> names) {
        return parkingBatchRepository.findAllByBaseInformationNameIn(names)
                .stream()
                .collect(toParkingMap());
    }

    private void updateSavedParkingLots(Map<String, Parking> parkingLots, Map<String, Parking> saved) {
        for (String parkingName : saved.keySet()) {
            Parking origin = saved.get(parkingName);
            Parking updated = parkingLots.get(parkingName);
            origin.update(updated);
        }
    }

    private void saveNewParkingLots(Map<String, Parking> parkingLots, Map<String, Parking> saved) {
        List<Parking> newParkingLots = parkingLots.keySet()
                .stream()
                .filter(parkingName -> !saved.containsKey(parkingName))
                .map(parkingLots::get)
                .toList();
        updateLocation(newParkingLots);
        parkingBatchRepository.saveWithBatch(newParkingLots);
    }

    private void updateLocation(List<Parking> newParkingLots) {
        for (Parking parking : newParkingLots) {
            if (parking.isLocationAvailable()) {
                continue;
            }
            Location locationByAddress = coordinateApiService.extractLocationByAddress(
                    parking.getBaseInformation().getAddress(),
                    parking.getLocation());
            parking.update(locationByAddress);
        }
    }

    @Scheduled(fixedRate = 30, timeUnit = TimeUnit.DAYS)
    public void autoUpdateNotOfferCurrentParking() {
        Map<String, Parking> parkingLots = readBy(parkingApiService -> !parkingApiService.offerCurrentParking());
        Map<String, Parking> saved = findAllByName(parkingLots.keySet());
        updateSavedParkingLots(parkingLots, saved);
        saveNewParkingLots(parkingLots, saved);
    }
}
