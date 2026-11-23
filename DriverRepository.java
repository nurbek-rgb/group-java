package kz.taxi.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import kz.taxi.model.*;

/** Low-level CRUD. Application state transitions must use DispatchService. */
public final class DriverRepository implements Repository<Driver> {
    private final Database db;
    public DriverRepository(Database db) { this.db = db; }
    public static Driver map(ResultSet rs) throws SQLException {
        return new Driver(rs.getLong("id"), rs.getString("name"), new Location(rs.getDouble("x"), rs.getDouble("y")),
                new Vehicle(rs.getString("model"), rs.getString("plate"), rs.getString("tariff")), DriverStatus.valueOf(rs.getString("status")));
    }
    @Override public Optional<Driver> findById(long id) { return db.query("SELECT * FROM drivers WHERE id=?", DriverRepository::map, id).stream().findFirst(); }
    @Override public List<Driver> findAll() { return db.query("SELECT * FROM drivers ORDER BY id", DriverRepository::map); }
    @Override public void save(Driver driver) {
        db.update("""
                INSERT INTO drivers(id,name,x,y,model,plate,tariff,status) VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET name=excluded.name,x=excluded.x,y=excluded.y,model=excluded.model,
                  plate=excluded.plate,tariff=excluded.tariff,status=excluded.status
                """, driver.id(), driver.name(), driver.location().x(), driver.location().y(),
                driver.vehicle().model(), driver.vehicle().plate(), driver.vehicle().tariff(), driver.status().name());
    }
    @Override public boolean delete(long id) { return db.update("DELETE FROM drivers WHERE id=?", id) > 0; }
}
