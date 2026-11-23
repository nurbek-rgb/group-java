package kz.taxi.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import kz.taxi.model.Tariff;

public final class SurgePricing implements PricingStrategy {
    private final BigDecimal multiplier;
    public SurgePricing(BigDecimal multiplier) {
        if (multiplier == null || multiplier.compareTo(BigDecimal.ONE) < 0)
            throw new IllegalArgumentException("Surge кемінде 1 болуы керек");
        this.multiplier = multiplier;
    }
    @Override public BigDecimal calculate(Tariff tariff, BigDecimal kilometres, BigDecimal minutes) {
        return StandardPricing.subtotal(tariff, kilometres, minutes).multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }
    public static BigDecimal forDemand(long waiting, long available) {
        if (waiting < 0 || available < 0) throw new IllegalArgumentException("Сан теріс болмауы керек");
        return BigDecimal.valueOf(waiting).divide(BigDecimal.valueOf(Math.max(1, available)), 2, RoundingMode.HALF_UP)
                .max(BigDecimal.ONE).min(new BigDecimal("3.00"));
    }
}
