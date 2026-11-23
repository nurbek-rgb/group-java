package kz.taxi.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kz.taxi.model.*;
import kz.taxi.pricing.*;
import kz.taxi.repository.*;

public final class DispatchService {
    private final Database db;
    private final DriverRepository drivers;
    private final OrderRepository orders;
    private final TripRepository trips;
    private final Clock clock;
    private final Duration responseTimeout;

    public DispatchService(Database db) { this(db, Clock.systemUTC(), Duration.ofSeconds(30)); }
    public DispatchService(Database db, Clock clock, Duration responseTimeout) {
        this.db = Objects.requireNonNull(db);
        this.clock = Objects.requireNonNull(clock);
        if (responseTimeout.isNegative() || responseTimeout.isZero()) throw new IllegalArgumentException("Күту уақыты оң болуы керек");
        this.responseTimeout = responseTimeout;
        drivers = new DriverRepository(db); orders = new OrderRepository(db); trips = new TripRepository(db);
    }

    public Passenger addPassenger(String name) {
        String validName = Checks.text(name);
        return db.transaction(d -> new Passenger(d.insert("INSERT INTO passengers(name) VALUES(?)", validName), validName));
    }
    public Driver addDriver(String name, Location location, Vehicle vehicle) {
        String validName = Checks.text(name);
        Objects.requireNonNull(location); Objects.requireNonNull(vehicle);
        return db.transaction(d -> {
            long id = d.insert("INSERT INTO drivers(name,x,y,model,plate,tariff,status) VALUES(?,?,?,?,?,?,?)",
                    validName, location.x(), location.y(), vehicle.model(), vehicle.plate(), vehicle.tariff(), DriverStatus.AVAILABLE.name());
            return drivers.findById(id).orElseThrow();
        });
    }
    public List<Passenger> passengers() {
        return db.query("SELECT * FROM passengers ORDER BY id", rs -> new Passenger(rs.getLong("id"), rs.getString("name")));
    }
    public List<Driver> drivers() { return drivers.findAll(); }
    public List<RideOrder> orders() { return orders.findAll(); }
    public List<Trip> trips() { return trips.findAll(); }
    public RideOrder order(long id) { return orders.findById(id).orElseThrow(() -> new IllegalArgumentException("Тапсырыс табылмады: " + id)); }
    public Trip trip(long id) { return trips.findById(id).orElseThrow(() -> new IllegalArgumentException("Сапар табылмады: " + id)); }

    public RideOrder submit(long passengerId, Location pickup, Location destination, Tariff tariff, BigDecimal minutes) {
        Checks.nonNegative(minutes); Objects.requireNonNull(pickup); Objects.requireNonNull(destination); Objects.requireNonNull(tariff);
        return db.transaction(d -> {
            if (d.query("SELECT id FROM passengers WHERE id=?", rs -> rs.getLong(1), passengerId).isEmpty())
                throw new IllegalArgumentException("Жолаушы табылмады");
            BigDecimal distance = BigDecimal.valueOf(pickup.distanceTo(destination)).setScale(3, RoundingMode.HALF_UP);
            long waiting = orders.findAll().stream().filter(o -> o.status() == OrderStatus.WAITING && o.tariff().equals(tariff.name())).count() + 1;
            long available = drivers.findAll().stream().filter(v -> v.status() == DriverStatus.AVAILABLE && v.vehicle().tariff().equals(tariff.name())).count();
            BigDecimal surge = SurgePricing.forDemand(waiting, available);
            PricingStrategy pricing = surge.compareTo(BigDecimal.ONE) == 0 ? new StandardPricing() : new SurgePricing(surge);
            BigDecimal price = pricing.calculate(tariff, distance, minutes);
            long id = d.insert("""
                    INSERT INTO orders(passenger_id,pickup_x,pickup_y,destination_x,destination_y,tariff,distance_km,minutes,surge,price,status,created_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """, passengerId, pickup.x(), pickup.y(), destination.x(), destination.y(), tariff.name(), distance.toPlainString(),
                    minutes.toPlainString(), surge.toPlainString(), price.toPlainString(), OrderStatus.WAITING.name(), clock.instant().toString());
            return order(id);
        });
    }

    /** Called only inside the dispatch transaction: selection and reservation cannot race. */
    private Optional<Driver> nearest(RideOrder order) {
        return drivers.findAll().stream()
                .filter(driver -> driver.status() == DriverStatus.AVAILABLE && driver.vehicle().tariff().equals(order.tariff()))
                .min(Comparator.comparingDouble((Driver driver) -> driver.location().distanceTo(order.pickup())).thenComparingLong(Driver::id));
    }

    public Optional<Trip> tryAssign(long orderId) {
        return db.transaction(d -> {
            RideOrder order = order(orderId);
            if (order.status() != OrderStatus.WAITING) return Optional.empty();
            Optional<Driver> candidate = nearest(order);
            if (candidate.isEmpty()) return Optional.empty();
            Driver driver = candidate.get();
            int changed = d.update("UPDATE drivers SET status='BUSY' WHERE id=? AND status='AVAILABLE'", driver.id());
            if (changed != 1) throw new IllegalStateException("Жүргізуші мәртебесі өзгерді");
            d.update("UPDATE orders SET status='ASSIGNED' WHERE id=?", orderId);
            Instant now = clock.instant();
            long id = d.insert("INSERT INTO trips(order_id,driver_id,status,price,offered_at,deadline) VALUES(?,?,?,?,?,?)",
                    orderId, driver.id(), TripStatus.CREATED.name(), order.price().toPlainString(), now.toString(), now.plus(responseTimeout).toString());
            return Optional.of(trip(id));
        });
    }

    public void accept(long tripId) {
        boolean expired = db.transaction(d -> {
            Trip trip = trip(tripId);
            require(trip.status() == TripStatus.CREATED && trip.acceptedAt() == null, "Бұл сапарды қабылдау мүмкін емес");
            Instant now = clock.instant();
            if (!now.isBefore(trip.deadline())) { expire(trip); return true; }
            d.update("UPDATE trips SET accepted_at=? WHERE id=?", now.toString(), tripId);
            return false;
        });
        // Throw after commit so the expired offer is actually released.
        if (expired) throw new IllegalStateException("Қабылдау уақыты өтті; тапсырыс қайта кезекке қойылды");
    }

    public void start(long tripId) {
        db.transaction(d -> {
            Trip trip = trip(tripId);
            require(trip.status() == TripStatus.CREATED && trip.acceptedAt() != null, "Алдымен сапарды қабылдау керек");
            d.update("UPDATE trips SET status='IN_PROGRESS',started_at=? WHERE id=?", clock.instant().toString(), tripId);
            d.update("UPDATE orders SET status='IN_PROGRESS' WHERE id=?", trip.orderId());
            return null;
        });
    }

    public void complete(long tripId) {
        db.transaction(d -> {
            Trip trip = trip(tripId);
            require(trip.status() == TripStatus.IN_PROGRESS, "Тек басталған сапарды аяқтауға болады");
            RideOrder order = order(trip.orderId());
            d.update("UPDATE trips SET status='COMPLETED',completed_at=? WHERE id=?", clock.instant().toString(), tripId);
            d.update("UPDATE orders SET status='COMPLETED' WHERE id=?", trip.orderId());
            d.update("UPDATE drivers SET status='AVAILABLE',x=?,y=? WHERE id=?", order.destination().x(), order.destination().y(), trip.driverId());
            return null;
        });
    }

    public void cancel(long orderId) {
        db.transaction(d -> {
            RideOrder order = order(orderId);
            require(order.status() == OrderStatus.WAITING || order.status() == OrderStatus.ASSIGNED,
                    "Тек күтіп тұрған немесе басталмаған тапсырысты тоқтатуға болады");
            d.query("SELECT * FROM trips WHERE order_id=? AND status='CREATED'", TripRepository::map, orderId).forEach(trip -> {
                d.update("UPDATE trips SET status='CANCELLED' WHERE id=?", trip.id());
                d.update("UPDATE drivers SET status='AVAILABLE' WHERE id=?", trip.driverId());
            });
            d.update("UPDATE orders SET status='CANCELLED' WHERE id=?", orderId);
            return null;
        });
    }

    public int expireOffers() {
        return db.transaction(d -> {
            List<Trip> expired = d.query("SELECT * FROM trips WHERE status='CREATED' AND accepted_at IS NULL", TripRepository::map)
                    .stream().filter(trip -> !clock.instant().isBefore(trip.deadline())).toList();
            expired.forEach(this::expire);
            return expired.size();
        });
    }
    private void expire(Trip trip) {
        db.update("UPDATE trips SET status='CANCELLED' WHERE id=?", trip.id());
        db.update("UPDATE orders SET status='WAITING' WHERE id=?", trip.orderId());
        // Do not immediately offer the same order to a driver who did not respond.
        db.update("UPDATE drivers SET status='OFFLINE' WHERE id=?", trip.driverId());
    }

    public void setOnline(long driverId, boolean online) {
        db.transaction(d -> {
            Driver driver = drivers.findById(driverId).orElseThrow(() -> new IllegalArgumentException("Жүргізуші табылмады"));
            require(driver.status() != DriverStatus.BUSY, "Белсенді сапары бар жүргізушінің мәртебесін өзгертуге болмайды");
            d.update("UPDATE drivers SET status=? WHERE id=?", online ? "AVAILABLE" : "OFFLINE", driverId);
            return null;
        });
    }

    public void rate(long tripId, int rating) {
        if (rating < 1 || rating > 5) throw new IllegalArgumentException("Рейтинг 1–5 аралығында болуы керек");
        db.transaction(d -> {
            Trip trip = trip(tripId);
            require(trip.status() == TripStatus.COMPLETED && trip.rating() == null, "Аяқталған сапарға бір рет баға беруге болады");
            d.update("UPDATE trips SET rating=? WHERE id=?", rating, tripId);
            return null;
        });
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
