package kz.taxi.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kz.taxi.model.*;

public final class TripRepository {
    private final Database db;
    public TripRepository(Database db) { this.db = db; }
    private static Instant time(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : Instant.parse(value);
    }
    public static Trip map(ResultSet rs) throws SQLException {
        int rating = rs.getInt("rating");
        Integer nullableRating = rs.wasNull() ? null : rating;
        return new Trip(rs.getLong("id"), rs.getLong("order_id"), rs.getLong("driver_id"), TripStatus.valueOf(rs.getString("status")),
                new BigDecimal(rs.getString("price")), time(rs, "offered_at"), time(rs, "deadline"), time(rs, "accepted_at"),
                time(rs, "started_at"), time(rs, "completed_at"), nullableRating);
    }
    public Optional<Trip> findById(long id) { return db.query("SELECT * FROM trips WHERE id=?", TripRepository::map, id).stream().findFirst(); }
    public List<Trip> findAll() { return db.query("SELECT * FROM trips ORDER BY id", TripRepository::map); }
}
