package com.parkingcomestrue.external.respository;

import com.parkingcomestrue.common.domain.parking.Location;
import com.parkingcomestrue.common.domain.parking.Parking;
import java.sql.PreparedStatement;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ParameterizedPreparedStatementSetter;
import org.springframework.dao.DataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Repository
public class ParkingBulkRepository {

    private final ParameterizedPreparedStatementSetter<Parking> PARKING_PARAMETERIZED_PREPARED_STATEMENT_SETTER = (PreparedStatement ps, Parking parking) -> {
        ps.setInt(1, parking.getFeePolicy().getBaseFee().getFee());
        ps.setInt(2, parking.getFeePolicy().getBaseTimeUnit().getTimeUnit());
        ps.setInt(3, parking.getFeePolicy().getExtraFee().getFee());
        ps.setInt(4, parking.getFeePolicy().getExtraTimeUnit().getTimeUnit());
        ps.setInt(5, parking.getFeePolicy().getDayMaximumFee().getFee());

        ps.setInt(6, parking.getSpace().getCapacity());
        ps.setInt(7, parking.getSpace().getCurrentParking());

        ps.setString(8, parking.getOperatingTime().getHolidayOperatingTime().toString());
        ps.setString(9, parking.getFreeOperatingTime().getHolidayFreeOperatingTime().toString());

        ps.setString(10, parking.getOperatingTime().getSaturdayOperatingTime().toString());
        ps.setString(11, parking.getFreeOperatingTime().getSaturdayFreeOperatingTime().toString());

        ps.setString(12, parking.getOperatingTime().getWeekdayOperatingTime().toString());
        ps.setString(13, parking.getFreeOperatingTime().getWeekdayFreeOperatingTime().toString());

        ps.setObject(14, parking.getCreatedAt());
        ps.setObject(15, parking.getUpdatedAt());

        ps.setString(16, parking.getBaseInformation().getAddress());
        ps.setString(17, parking.getBaseInformation().getName());
        ps.setString(18, parking.getBaseInformation().getTel());
        ps.setString(19, parking.getBaseInformation().getOperationType().name());
        ps.setString(20, parking.getBaseInformation().getParkingType().name());
        ps.setString(21, parking.getBaseInformation().getPayTypesName());
        ps.setString(22, toWKT(parking.getLocation()));
    };

    private String toWKT(Location location) {
        return "POINT(" + location.getLatitude() + " " + location.getLongitude() + ")";
    }

    private final JdbcTemplate jdbcTemplate;

    @Retryable(
            retryFor = DataAccessException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    @Transactional
    public void saveAllWithBulk(List<Parking> parkingLots) {
        String sql = "INSERT INTO parking "
                + "(base_fee, base_time_unit, extra_fee, extra_time_unit, day_maximum_fee, "
                + "capacity, current_parking, "
                + "holiday_operating_time, holiday_free_operating_time, "
                + "saturday_operating_time, saturday_free_operating_time, "
                + "weekday_operating_time, weekday_free_operating_time, "
                + "created_at, updated_at, "
                + "address, name, tel, operation_type, parking_type, pay_types, location) "
                + "VALUES "
                + "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ST_GeomFromText(?, 4326))";

        jdbcTemplate.batchUpdate(
                sql,
                parkingLots,
                parkingLots.size(),
                PARKING_PARAMETERIZED_PREPARED_STATEMENT_SETTER
        );
    }
}
