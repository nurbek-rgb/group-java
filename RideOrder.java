package kz.taxi.model;

import java.math.BigDecimal;
import java.time.Instant;

public record RideOrder(long id, long passengerId, Location pickup, Location destination,
                        String tariff, BigDecimal distanceKm, BigDecimal minutes,
                        BigDecimal surge, BigDecimal price, OrderStatus status, Instant createdAt) {}
