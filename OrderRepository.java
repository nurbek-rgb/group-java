package kz.taxi.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kz.taxi.model.*;

public final class OrderRepository implements Repository<RideOrder> {
    private final Database db;
    public OrderRepository(Database db) { this.db = db; }
    public static RideOrder map(ResultSet rs) throws SQLException {
        return new RideOrder(rs.getLong("id"), rs.getLong("passenger_id"),
                new Location(rs.getDouble("pickup_x"), rs.getDouble("pickup_y")),
                new Location(rs.getDouble("destination_x"), rs.getDouble("destination_y")), rs.getString("tariff"),
                new BigDecimal(rs.getString("distance_km")), new BigDecimal(rs.getString("minutes")),
                new BigDecimal(rs.getString("surge")), new BigDecimal(rs.getString("price")),
                OrderStatus.valueOf(rs.getString("status")), Instant.parse(rs.getString("created_at")));
    }
    @Override public Optional<RideOrder> findById(long id) { return db.query("SELECT * FROM orders WHERE id=?", OrderRepository::map, id).stream().findFirst(); }
    @Override public List<RideOrder> findAll() { return db.query("SELECT * FROM orders ORDER BY id", OrderRepository::map); }
    @Override public void save(RideOrder order) {
        db.update("""
                INSERT INTO orders(id,passenger_id,pickup_x,pickup_y,destination_x,destination_y,tariff,distance_km,minutes,surge,price,status,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET
                  passenger_id=excluded.passenger_id,pickup_x=excluded.pickup_x,pickup_y=excluded.pickup_y,
                  destination_x=excluded.destination_x,destination_y=excluded.destination_y,tariff=excluded.tariff,
                  distance_km=excluded.distance_km,minutes=excluded.minutes,surge=excluded.surge,price=excluded.price,
                  status=excluded.status,created_at=excluded.created_at
                """, order.id(), order.passengerId(), order.pickup().x(), order.pickup().y(), order.destination().x(), order.destination().y(),
                order.tariff(), order.distanceKm().toPlainString(), order.minutes().toPlainString(), order.surge().toPlainString(),
                order.price().toPlainString(), order.status().name(), order.createdAt().toString());
    }
    @Override public boolean delete(long id) { return db.update("DELETE FROM orders WHERE id=?", id) > 0; }
}
