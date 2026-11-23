package kz.taxi.model;

import java.math.BigDecimal;

public record Tariff(String name, BigDecimal base, BigDecimal perKm, BigDecimal perMinute) {
    public static final Tariff ECONOMY = new Tariff("ECONOMY", new BigDecimal("400"), new BigDecimal("100"), new BigDecimal("20"));
    public static final Tariff COMFORT = new Tariff("COMFORT", new BigDecimal("700"), new BigDecimal("150"), new BigDecimal("30"));
    public Tariff {
        name = Checks.text(name);
        Checks.nonNegative(base); Checks.nonNegative(perKm); Checks.nonNegative(perMinute);
    }
}
