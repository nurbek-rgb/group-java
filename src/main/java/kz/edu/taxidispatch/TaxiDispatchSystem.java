package kz.edu.taxidispatch;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Учебный MVP диспетчерской службы такси.
 * Основные части проекта собраны в одном файле, чтобы их было проще объяснять на защите.
 */
public class TaxiDispatchSystem {

    public static void main(String[] args) throws Exception {
        Database database = new Database("taxi_dispatch.db");
        database.initialize();
        database.clearDemoData();

        DriverRepository driverRepository = new DriverRepository(database);
        TaxiDao taxiDao = new TaxiDao(database);

        Driver arman = new Driver("Арман", new Point(1, 1), "Toyota Camry", "777 KZ 01", 4.9);
        Driver dana = new Driver("Дана", new Point(8, 7), "Hyundai Accent", "505 KZ 01", 4.8);
        Driver olzhas = new Driver("Олжас", new Point(4, 3), "Kia K5", "101 KZ 01", 4.7);
        List<Driver> drivers = new ArrayList<>(List.of(arman, dana, olzhas));
        drivers.forEach(driverRepository::save);

        Passenger passenger = new Passenger("Нұрбек", "+7 700 000 00 00");
        taxiDao.savePassenger(passenger);

        PricingStrategy standard = new StandardPricing();
        PricingStrategy surge = new SurgePricing(standard, new BigDecimal("1.25"));
        DispatcherService dispatcher = new DispatcherService(drivers, taxiDao, surge);
        dispatcher.start(2);

        RideOrder first = new RideOrder(passenger, new Point(2, 2), new Point(10, 8), Tariff.COMFORT);
        RideOrder second = new RideOrder(passenger, new Point(7, 6), new Point(2, 1), Tariff.ECONOMY);
        dispatcher.submit(first);
        dispatcher.submit(second);

        Trip firstTrip = first.assignment().get(5, TimeUnit.SECONDS);
        Trip secondTrip = second.assignment().get(5, TimeUnit.SECONDS);

        System.out.println("=== Такси диспетчерлік жүйесі: MVP ===");
        printTrip(firstTrip);
        printTrip(secondTrip);

        dispatcher.completeTrip(firstTrip);
        dispatcher.completeTrip(secondTrip);
        dispatcher.stop();

        List<Trip> trips = taxiDao.findAllTrips();
        ReportService reportService = new ReportService();
        Path report = reportService.exportCsv(trips, Path.of("reports", "trips.csv"));

        System.out.println("\nСапарлар тариф бойынша: " + reportService.countByTariff(trips));
        System.out.println("CSV есеп: " + report.toAbsolutePath());
        System.out.println("SQLite база: " + database.path().toAbsolutePath());
    }

    private static void printTrip(Trip trip) {
        System.out.printf("Заказ №%d -> водитель %s, тариф %s, цена %s тг%n",
                trip.order().id(), trip.driver().name(), trip.order().tariff(), trip.price());
    }

    public enum DriverStatus { AVAILABLE, BUSY, OFFLINE }
    public enum OrderStatus { WAITING, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED }
    public enum TripStatus { CREATED, IN_PROGRESS, COMPLETED }
    public enum Tariff { ECONOMY, COMFORT }

    public record Point(double x, double y) {
        public double distanceTo(Point other) {
            double dx = x - other.x;
            double dy = y - other.y;
            return Math.sqrt(dx * dx + dy * dy);
        }
    }

    public abstract static class User {
        private long id;
        private final String name;

        protected User(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Имя не должно быть пустым");
            }
            this.name = name;
        }

        public long id() { return id; }
        public String name() { return name; }
        void setId(long id) { this.id = id; }
    }

    public static final class Passenger extends User {
        private final String phone;

        public Passenger(String name, String phone) {
            super(name);
            this.phone = phone;
        }

        public String phone() { return phone; }
    }

    public static final class Driver extends User {
        private final Point location;
        private final String carModel;
        private final String plateNumber;
        private final double rating;
        private final ReentrantLock assignmentLock = new ReentrantLock();
        private volatile DriverStatus status = DriverStatus.AVAILABLE;

        public Driver(String name, Point location, String carModel, String plateNumber, double rating) {
            super(name);
            this.location = location;
            this.carModel = carModel;
            this.plateNumber = plateNumber;
            this.rating = rating;
        }

        public Point location() { return location; }
        public String carModel() { return carModel; }
        public String plateNumber() { return plateNumber; }
        public double rating() { return rating; }
        public DriverStatus status() { return status; }

        public boolean tryReserve() {
            assignmentLock.lock();
            try {
                if (status != DriverStatus.AVAILABLE) {
                    return false;
                }
                status = DriverStatus.BUSY;
                return true;
            } finally {
                assignmentLock.unlock();
            }
        }

        public void release() {
            assignmentLock.lock();
            try {
                status = DriverStatus.AVAILABLE;
            } finally {
                assignmentLock.unlock();
            }
        }
    }

    public static final class RideOrder {
        private long id;
        private final Passenger passenger;
        private final Point pickup;
        private final Point destination;
        private final Tariff tariff;
        private volatile OrderStatus status = OrderStatus.WAITING;
        private final CompletableFuture<Trip> assignment = new CompletableFuture<>();

        public RideOrder(Passenger passenger, Point pickup, Point destination, Tariff tariff) {
            this.passenger = passenger;
            this.pickup = pickup;
            this.destination = destination;
            this.tariff = tariff;
        }

        public long id() { return id; }
        public Passenger passenger() { return passenger; }
        public Point pickup() { return pickup; }
        public Point destination() { return destination; }
        public Tariff tariff() { return tariff; }
        public OrderStatus status() { return status; }
        public CompletableFuture<Trip> assignment() { return assignment; }
        void setId(long id) { this.id = id; }

        void markAssigned(Trip trip) {
            status = OrderStatus.ASSIGNED;
            assignment.complete(trip);
        }

        void markCompleted() {
            status = OrderStatus.COMPLETED;
        }

        public double distanceKm() {
            return pickup.distanceTo(destination);
        }
    }

    public static final class Trip {
        private long id;
        private final RideOrder order;
        private final Driver driver;
        private final BigDecimal price;
        private TripStatus status = TripStatus.CREATED;

        public Trip(RideOrder order, Driver driver, BigDecimal price) {
            this.order = order;
            this.driver = driver;
            this.price = price;
        }

        public long id() { return id; }
        public RideOrder order() { return order; }
        public Driver driver() { return driver; }
        public BigDecimal price() { return price; }
        public TripStatus status() { return status; }
        void setId(long id) { this.id = id; }
        void complete() { status = TripStatus.COMPLETED; }
    }

    public interface PricingStrategy {
        BigDecimal calculate(RideOrder order, int durationMinutes);
    }

    public static final class StandardPricing implements PricingStrategy {
        @Override
        public BigDecimal calculate(RideOrder order, int durationMinutes) {
            BigDecimal base = order.tariff() == Tariff.COMFORT
                    ? new BigDecimal("800") : new BigDecimal("500");
            BigDecimal perKm = order.tariff() == Tariff.COMFORT
                    ? new BigDecimal("130") : new BigDecimal("100");
            BigDecimal perMinute = order.tariff() == Tariff.COMFORT
                    ? new BigDecimal("35") : new BigDecimal("25");

            BigDecimal distance = BigDecimal.valueOf(order.distanceKm());
            return base.add(distance.multiply(perKm))
                    .add(perMinute.multiply(BigDecimal.valueOf(durationMinutes)))
                    .setScale(2, RoundingMode.HALF_UP);
        }
    }

    public static final class SurgePricing implements PricingStrategy {
        private final PricingStrategy basicPricing;
        private final BigDecimal multiplier;

        public SurgePricing(PricingStrategy basicPricing, BigDecimal multiplier) {
            if (multiplier.compareTo(BigDecimal.ONE) < 0) {
                throw new IllegalArgumentException("Surge коэффициент не может быть меньше 1");
            }
            this.basicPricing = basicPricing;
            this.multiplier = multiplier;
        }

        @Override
        public BigDecimal calculate(RideOrder order, int durationMinutes) {
            return basicPricing.calculate(order, durationMinutes)
                    .multiply(multiplier)
                    .setScale(2, RoundingMode.HALF_UP);
        }
    }

    public interface Repository<T> {
        T save(T item);
        Optional<T> findById(long id);
        List<T> findAll();
    }

    public static final class Database {
        private final Path path;

        public Database(String fileName) {
            this.path = Path.of(fileName);
        }

        public Path path() { return path; }

        Connection open() throws SQLException {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
            }
            return connection;
        }

        public void initialize() {
            String schema = """
                    CREATE TABLE IF NOT EXISTS passengers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        phone TEXT NOT NULL
                    );
                    CREATE TABLE IF NOT EXISTS drivers (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        car_model TEXT NOT NULL,
                        plate_number TEXT NOT NULL UNIQUE,
                        rating REAL NOT NULL,
                        status TEXT NOT NULL
                    );
                    CREATE TABLE IF NOT EXISTS orders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        passenger_id INTEGER NOT NULL,
                        pickup_x REAL NOT NULL,
                        pickup_y REAL NOT NULL,
                        destination_x REAL NOT NULL,
                        destination_y REAL NOT NULL,
                        tariff TEXT NOT NULL,
                        status TEXT NOT NULL,
                        FOREIGN KEY(passenger_id) REFERENCES passengers(id)
                    );
                    CREATE TABLE IF NOT EXISTS trips (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        order_id INTEGER NOT NULL UNIQUE,
                        driver_id INTEGER NOT NULL,
                        price TEXT NOT NULL,
                        status TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY(order_id) REFERENCES orders(id),
                        FOREIGN KEY(driver_id) REFERENCES drivers(id)
                    );
                    """;
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                for (String command : schema.split(";")) {
                    if (!command.isBlank()) {
                        statement.execute(command);
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Не удалось создать таблицы", e);
            }
        }

        public void clearDemoData() {
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM trips");
                statement.executeUpdate("DELETE FROM orders");
                statement.executeUpdate("DELETE FROM drivers");
                statement.executeUpdate("DELETE FROM passengers");
            } catch (SQLException e) {
                throw new IllegalStateException("Не удалось очистить демонстрационные данные", e);
            }
        }
    }

    public static final class DriverRepository implements Repository<Driver> {
        private final Database database;

        public DriverRepository(Database database) {
            this.database = database;
        }

        @Override
        public Driver save(Driver driver) {
            String sql = "INSERT INTO drivers(name, x, y, car_model, plate_number, rating, status) VALUES(?,?,?,?,?,?,?)";
            try (Connection connection = database.open();
                 PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, driver.name());
                statement.setDouble(2, driver.location().x());
                statement.setDouble(3, driver.location().y());
                statement.setString(4, driver.carModel());
                statement.setString(5, driver.plateNumber());
                statement.setDouble(6, driver.rating());
                statement.setString(7, driver.status().name());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) driver.setId(keys.getLong(1));
                }
                return driver;
            } catch (SQLException e) {
                throw new IllegalStateException("Не удалось сохранить водителя", e);
            }
        }

        @Override
        public Optional<Driver> findById(long id) {
            return findAll().stream().filter(driver -> driver.id() == id).findFirst();
        }

        @Override
        public List<Driver> findAll() {
            List<Driver> drivers = new ArrayList<>();
            String sql = "SELECT * FROM drivers ORDER BY id";
            try (Connection connection = database.open();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Driver driver = new Driver(result.getString("name"),
                            new Point(result.getDouble("x"), result.getDouble("y")),
                            result.getString("car_model"), result.getString("plate_number"),
                            result.getDouble("rating"));
                    driver.setId(result.getLong("id"));
                    if (DriverStatus.valueOf(result.getString("status")) == DriverStatus.BUSY) {
                        driver.tryReserve();
                    }
                    drivers.add(driver);
                }
                return drivers;
            } catch (SQLException e) {
                throw new IllegalStateException("Не удалось прочитать водителей", e);
            }
        }
    }

    public static final class TaxiDao {
        private final Database database;

        public TaxiDao(Database database) {
            this.database = database;
        }

        public Passenger savePassenger(Passenger passenger) {
            String sql = "INSERT INTO passengers(name, phone) VALUES(?,?)";
            try (Connection connection = database.open();
                 PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, passenger.name());
                statement.setString(2, passenger.phone());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) passenger.setId(keys.getLong(1));
                }
                return passenger;
            } catch (SQLException e) {
                throw new IllegalStateException("Не удалось сохранить пассажира", e);
            }
        }

        public RideOrder saveOrder(RideOrder order) {
            String sql = "INSERT INTO orders(passenger_id, pickup_x, pickup_y, destination_x, destination_y, tariff, status) VALUES(?,?,?,?,?,?,?)";
            try (Connection connection = database.open();
                 PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, order.passenger().id());
                statement.setDouble(2, order.pickup().x());
                statement.setDouble(3, order.pickup().y());
                statement.setDouble(4, order.destination().x());
                statement.setDouble(5, order.destination().y());
                statement.setString(6, order.tariff().name());
                statement.setString(7, order.status().name());
                statement.executeUpdate();
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) order.setId(keys.getLong(1));
                }
                return order;
            } catch (SQLException e) {
                throw new IllegalStateException("Не удалось сохранить заказ", e);
            }
        }

        /** Одна транзакция меняет водителя, заказ и создаёт поездку. */
        public Trip assignDriver(RideOrder order, Driver driver, BigDecimal price) {
            String updateDriver = "UPDATE drivers SET status='BUSY' WHERE id=? AND status='AVAILABLE'";
            String updateOrder = "UPDATE orders SET status='ASSIGNED' WHERE id=?";
            String insertTrip = "INSERT INTO trips(order_id, driver_id, price, status, created_at) VALUES(?,?,?,?,?)";

            try (Connection connection = database.open()) {
                connection.setAutoCommit(false);
                try (PreparedStatement driverStatement = connection.prepareStatement(updateDriver);
                     PreparedStatement orderStatement = connection.prepareStatement(updateOrder);
                     PreparedStatement tripStatement = connection.prepareStatement(insertTrip, Statement.RETURN_GENERATED_KEYS)) {
                    driverStatement.setLong(1, driver.id());
                    if (driverStatement.executeUpdate() != 1) {
                        throw new SQLException("Водитель уже занят");
                    }

                    orderStatement.setLong(1, order.id());
                    orderStatement.executeUpdate();

                    tripStatement.setLong(1, order.id());
                    tripStatement.setLong(2, driver.id());
                    tripStatement.setString(3, price.toPlainString());
                    tripStatement.setString(4, TripStatus.CREATED.name());
                    tripStatement.setString(5, LocalDateTime.now().toString());
                    tripStatement.executeUpdate();

                    Trip trip = new Trip(order, driver, price);
                    try (ResultSet keys = tripStatement.getGeneratedKeys()) {
                        if (keys.next()) trip.setId(keys.getLong(1));
                    }
                    connection.commit();
                    return trip;
                } catch (Exception e) {
                    connection.rollback();
                    throw e;
                }
            } catch (Exception e) {
                throw new IllegalStateException("Транзакция назначения не выполнена", e);
            }
        }

        public void completeTrip(Trip trip) {
            String tripSql = "UPDATE trips SET status='COMPLETED' WHERE id=?";
            String orderSql = "UPDATE orders SET status='COMPLETED' WHERE id=?";
            String driverSql = "UPDATE drivers SET status='AVAILABLE' WHERE id=?";
            try (Connection connection = database.open()) {
                connection.setAutoCommit(false);
                try (PreparedStatement tripStatement = connection.prepareStatement(tripSql);
                     PreparedStatement orderStatement = connection.prepareStatement(orderSql);
                     PreparedStatement driverStatement = connection.prepareStatement(driverSql)) {
                    tripStatement.setLong(1, trip.id());
                    tripStatement.executeUpdate();
                    orderStatement.setLong(1, trip.order().id());
                    orderStatement.executeUpdate();
                    driverStatement.setLong(1, trip.driver().id());
                    driverStatement.executeUpdate();
                    connection.commit();
                } catch (Exception e) {
                    connection.rollback();
                    throw e;
                }
            } catch (Exception e) {
                throw new IllegalStateException("Не удалось завершить поездку", e);
            }
        }

        public List<Trip> findAllTrips() {
            String sql = """
                    SELECT t.id trip_id, t.price, t.status trip_status,
                           o.id order_id, o.tariff, o.status order_status,
                           d.id driver_id, d.name driver_name, d.x, d.y,
                           d.car_model, d.plate_number, d.rating
                    FROM trips t
                    JOIN orders o ON o.id=t.order_id
                    JOIN drivers d ON d.id=t.driver_id
                    ORDER BY t.id
                    """;
            List<Trip> trips = new ArrayList<>();
            try (Connection connection = database.open();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    Passenger reportPassenger = new Passenger("Пассажир", "-");
                    RideOrder order = new RideOrder(reportPassenger, new Point(0, 0), new Point(0, 0),
                            Tariff.valueOf(result.getString("tariff")));
                    order.setId(result.getLong("order_id"));
                    Driver driver = new Driver(result.getString("driver_name"),
                            new Point(result.getDouble("x"), result.getDouble("y")),
                            result.getString("car_model"), result.getString("plate_number"), result.getDouble("rating"));
                    driver.setId(result.getLong("driver_id"));
                    Trip trip = new Trip(order, driver, new BigDecimal(result.getString("price")));
                    trip.setId(result.getLong("trip_id"));
                    if (TripStatus.valueOf(result.getString("trip_status")) == TripStatus.COMPLETED) trip.complete();
                    trips.add(trip);
                }
                return trips;
            } catch (SQLException e) {
                throw new IllegalStateException("Не удалось получить поездки", e);
            }
        }
    }

    public static final class DispatcherService {
        private final List<Driver> drivers;
        private final TaxiDao taxiDao;
        private final PricingStrategy pricing;
        private final BlockingQueue<RideOrder> orderQueue = new LinkedBlockingQueue<>();
        private final Map<Long, RideOrder> orders = new ConcurrentHashMap<>();
        private final Set<Long> activeDriverIds = ConcurrentHashMap.newKeySet();
        private volatile boolean running;
        private ExecutorService workers;

        public DispatcherService(List<Driver> drivers, TaxiDao taxiDao, PricingStrategy pricing) {
            this.drivers = drivers;
            this.taxiDao = taxiDao;
            this.pricing = pricing;
        }

        public void start(int workerCount) {
            running = true;
            workers = Executors.newFixedThreadPool(workerCount);
            for (int i = 0; i < workerCount; i++) {
                workers.submit(this::workerLoop);
            }
        }

        public void submit(RideOrder order) {
            taxiDao.saveOrder(order);
            orders.put(order.id(), order);
            orderQueue.offer(order);
        }

        private void workerLoop() {
            while (running || !orderQueue.isEmpty()) {
                try {
                    RideOrder order = orderQueue.poll(200, TimeUnit.MILLISECONDS);
                    if (order != null && assignNow(order).isEmpty() && running) {
                        // Если все водители заняты, заказ возвращается в очередь и ждёт.
                        TimeUnit.MILLISECONDS.sleep(100);
                        orderQueue.offer(order);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        public Optional<Trip> assignNow(RideOrder order) {
            List<Driver> candidates = drivers.stream()
                    .filter(driver -> driver.status() == DriverStatus.AVAILABLE)
                    .sorted(Comparator.comparingDouble(driver -> driver.location().distanceTo(order.pickup())))
                    .toList();

            for (Driver driver : candidates) {
                if (!driver.tryReserve()) continue;
                try {
                    BigDecimal price = pricing.calculate(order, 15);
                    Trip trip = taxiDao.assignDriver(order, driver, price);
                    activeDriverIds.add(driver.id());
                    order.markAssigned(trip);
                    return Optional.of(trip);
                } catch (RuntimeException e) {
                    driver.release();
                    throw e;
                }
            }
            return Optional.empty();
        }

        public void completeTrip(Trip trip) {
            taxiDao.completeTrip(trip);
            trip.complete();
            trip.order().markCompleted();
            trip.driver().release();
            activeDriverIds.remove(trip.driver().id());
        }

        public Set<Long> activeDriverIds() {
            return Set.copyOf(activeDriverIds);
        }

        public void stop() throws InterruptedException {
            running = false;
            if (workers != null) {
                workers.shutdown();
                workers.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    public static final class ReportService {
        public Map<Tariff, Long> countByTariff(List<Trip> trips) {
            return trips.stream().collect(Collectors.groupingBy(
                    trip -> trip.order().tariff(), Collectors.counting()));
        }

        public List<String> driverNames(List<Trip> trips) {
            return trips.stream().map(trip -> trip.driver().name()).distinct().toList();
        }

        public Path exportCsv(List<Trip> trips, Path target) {
            try {
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                    writer.write("trip_id,order_id,driver,tariff,price,status");
                    writer.newLine();
                    for (Trip trip : trips) {
                        writer.write(String.join(",",
                                String.valueOf(trip.id()),
                                String.valueOf(trip.order().id()),
                                trip.driver().name(),
                                trip.order().tariff().name(),
                                trip.price().toPlainString(),
                                trip.status().name()));
                        writer.newLine();
                    }
                }
                return target;
            } catch (IOException e) {
                throw new IllegalStateException("Не удалось создать CSV отчёт", e);
            }
        }
    }
}
