package com.parkingcomestrue.external.scheduler;

import com.parkingcomestrue.common.domain.parking.Location;
import com.parkingcomestrue.common.domain.parking.Parking;
import com.parkingcomestrue.external.api.coordinate.CoordinateApiService;
import com.parkingcomestrue.external.api.HealthCheckResponse;
import com.parkingcomestrue.external.api.parkingapi.ParkingApiService;
import com.parkingcomestrue.external.respository.ParkingBatchRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class ParkingUpdateScheduler {

    private static final int BATCH_SIZE = 2000;

    private final List<ParkingApiService> parkingApiServices;
    private final CoordinateApiService coordinateApiService;
    private final ParkingBatchRepository parkingBatchRepository;
    private final ThreadPoolTaskExecutor apiTaskExecutor;

    @Scheduled(cron = "0 */30 * * * *")
    public void autoUpdateOfferCurrentParking() {
        List<List<Parking>> failedChunks = new ArrayList<>();
        processApis(ParkingApiService::offerCurrentParking, failedChunks);
        retryFailedChunks(failedChunks);
    }

    @Scheduled(fixedRate = 30, timeUnit = TimeUnit.DAYS)
    public void autoUpdateNotOfferCurrentParking() {
        List<List<Parking>> failedChunks = new ArrayList<>();
        processApis(api -> !api.offerCurrentParking(), failedChunks);
        retryFailedChunks(failedChunks);
    }

    private void processApis(Predicate<ParkingApiService> filter, List<List<Parking>> failedChunks) {
        for (ParkingApiService parkingApi : filterBy(filter)) {
            HealthCheckResponse health = parkingApi.check();
            if (health.isHealthy()) {
                processApiInChunks(parkingApi, health.getTotalSize(), failedChunks);
            }
        }
    }

    private void processApiInChunks(ParkingApiService parkingApi, int totalSize, List<List<Parking>> failedChunks) {
        int readSize = parkingApi.getReadSize();
        int pagesPerChunk = Math.max(1, BATCH_SIZE / readSize);
        int lastPage = calculateLastPageNumber(totalSize, readSize);

        for (int startPage = 1; startPage <= lastPage; startPage += pagesPerChunk) {
            int endPage = Math.min(startPage + pagesPerChunk - 1, lastPage);
            List<Parking> chunk = fetchChunk(parkingApi, startPage, endPage, readSize);
            persistChunk(chunk, parkingApi, startPage, failedChunks);
        }
    }

    private List<Parking> fetchChunk(ParkingApiService parkingApi, int startPage, int endPage, int readSize) {
        List<CompletableFuture<List<Parking>>> futures =
                Stream.iterate(startPage, i -> i <= endPage, i -> i + 1)
                        .map(i -> CompletableFuture
                                .supplyAsync(() -> parkingApi.read(i, readSize), apiTaskExecutor)
                                .exceptionally(throwable -> {
                                    log.error("페이지 {} fetch 실패. API={}, error={}",
                                            i, parkingApi.getClass().getSimpleName(), throwable.getMessage());
                                    return List.of();
                                }))
                        .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .flatMap(Collection::stream)
                .toList();
    }

    private void persistChunk(List<Parking> chunk, ParkingApiService api, int startPage,
                               List<List<Parking>> failedChunks) {
        try {
            Map<String, Parking> chunkMap = chunk.stream().collect(toParkingMap());
            Map<String, Parking> saved = findAllByName(chunkMap.keySet());
            updateSavedParkingLots(chunkMap, saved);
            saveNewParkingLots(chunkMap, saved);
        } catch (Exception e) {
            log.error("청크 저장 실패. API={}, startPage={}, size={}",
                    api.getClass().getSimpleName(), startPage, chunk.size(), e);
            failedChunks.add(chunk);
        }
    }

    private void retryFailedChunks(List<List<Parking>> failedChunks) {
        if (failedChunks.isEmpty()) {
            return;
        }
        log.info("실패한 청크 재시도. count={}", failedChunks.size());
        for (List<Parking> chunk : failedChunks) {
            try {
                Map<String, Parking> chunkMap = chunk.stream().collect(toParkingMap());
                Map<String, Parking> saved = findAllByName(chunkMap.keySet());
                updateSavedParkingLots(chunkMap, saved);
                saveNewParkingLots(chunkMap, saved);
            } catch (Exception e) {
                log.error("청크 재시도 실패. size={}", chunk.size(), e);
            }
        }
    }

    private List<ParkingApiService> filterBy(Predicate<ParkingApiService> filter) {
        return parkingApiServices.stream()
                .filter(filter)
                .toList();
    }

    private int calculateLastPageNumber(int totalSize, int readSize) {
        int lastPageNumber = totalSize / readSize;
        if (totalSize % readSize == 0) {
            return lastPageNumber;
        }
        return lastPageNumber + 1;
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
        for (Map.Entry<String, Parking> entry : saved.entrySet()) {
            Parking origin = entry.getValue();
            Parking updated = parkingLots.get(entry.getKey());
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
}
