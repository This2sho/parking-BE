package com.parkingcomestrue.external.respository;

import com.parkingcomestrue.common.domain.parking.Parking;
import com.parkingcomestrue.common.domain.parking.repository.ParkingRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Repository;

@Slf4j
@RequiredArgsConstructor
@Repository
public class ParkingBatchRepositoryImpl implements ParkingBatchRepository {

    private final int BATCH_SIZE = 2000;

    private final ParkingBulkRepository parkingBulkRepository;
    private final ParkingRepository parkingRepository;

    @Override
    public void saveWithBatch(List<Parking> parkingLots) {
        for (int i = 0; i < parkingLots.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, parkingLots.size());
            parkingBulkRepository.saveAllWithBulk(parkingLots.subList(i, end));
        }
    }

    @Override
    public Optional<Parking> findById(Long id) {
        return parkingRepository.findById(id);
    }

    @Override
    public Set<Parking> findAllByBaseInformationNameIn(Set<String> parkingNames) {
        return parkingRepository.findAllByBaseInformationNameIn(parkingNames);
    }

    @Override
    public void saveAll(Iterable<Parking> parkingLots) {
        parkingRepository.saveAll(parkingLots);
    }

    @Override
    public List<Parking> findAroundParkingLotsOrderByDistance(Point point, int radius) {
        return parkingRepository.findAroundParkingLotsOrderByDistance(point, radius);
    }

    @Override
    public List<Parking> findAroundParkingLots(Point point, int radius) {
        return parkingRepository.findAroundParkingLots(point, radius);
    }
}
