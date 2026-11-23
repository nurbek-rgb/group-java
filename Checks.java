package kz.taxi.model;

import java.math.BigDecimal;
import java.util.Objects;

public final class Checks {
    private Checks() {}
    public static String text(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Мәтін бос болмауы керек");
        return value.strip();
    }
    public static long id(long value) {
        if (value <= 0) throw new IllegalArgumentException("ID оң сан болуы керек");
        return value;
    }
    public static BigDecimal nonNegative(BigDecimal value) {
        Objects.requireNonNull(value, "Сан қажет");
        if (value.signum() < 0) throw new IllegalArgumentException("Сан теріс болмауы керек");
        return value;
    }
}
