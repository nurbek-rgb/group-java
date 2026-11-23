package kz.taxi.model;

/** Cartesian simulation coordinates in kilometres, not GPS. */
public record Location(double x, double y) {
    public Location {
        if (!Double.isFinite(x) || !Double.isFinite(y) || Math.abs(x) > 1_000_000 || Math.abs(y) > 1_000_000)
            throw new IllegalArgumentException("Координата шектен тыс немесе сан емес");
    }
    public double distanceTo(Location other) { return Math.hypot(x - other.x, y - other.y); }
}
