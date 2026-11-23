package kz.taxi.model;

import java.math.BigDecimal;
import java.time.Instant;

public record Trip(long id, long orderId, long driverId, TripStatus status, BigDecimal price,
                   Instant offeredAt, Instant deadline, Instant acceptedAt, Instant startedAt,
                   Instant completedAt, Integer rating) {}
