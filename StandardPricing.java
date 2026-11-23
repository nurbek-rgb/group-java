package kz.taxi.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import kz.taxi.model.Checks;
import kz.taxi.model.Tariff;

public final class StandardPricing implements PricingStrategy {
    @Override public BigDecimal calculate(Tariff tariff, BigDecimal kilometres, BigDecimal minutes) {
        return subtotal(tariff, kilometres, minutes).setScale(2, RoundingMode.HALF_UP);
    }
    static BigDecimal subtotal(Tariff tariff, BigDecimal kilometres, BigDecimal minutes) {
        return tariff.base().add(tariff.perKm().multiply(Checks.nonNegative(kilometres)))
                .add(tariff.perMinute().multiply(Checks.nonNegative(minutes)));
    }
}
