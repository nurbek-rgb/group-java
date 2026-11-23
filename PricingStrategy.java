package kz.taxi.pricing;

import java.math.BigDecimal;
import kz.taxi.model.Tariff;

public interface PricingStrategy {
    BigDecimal calculate(Tariff tariff, BigDecimal kilometres, BigDecimal minutes);
}
