package kz.edu.taxidispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaxiDispatchSystemTest {

    @TempDir
    Path tempDir;

    private TaxiDispatchSystem.Database database;
    private TaxiDispatchSystem.DriverRepository driverRepository;
    private TaxiDispatchSystem.TaxiDao taxiDao;
    private TaxiDispatchSystem.Passenger passenger;

    @BeforeEach
    void prepareDatabase() {
        database = new TaxiDispatchSystem.Database(tempDir.resolve("test.db").toString());
        database.initialize();
        driverRepository = new TaxiDispatchSystem.DriverRepository(database);
        taxiDao = new TaxiDispatchSystem.TaxiDao(database);
        passenger = new TaxiDispatchSystem.Passenger("Тест", "+7 700 111 22 33");
        taxiDao.savePassenger(passenger);
    }

    private TaxiDispatchSystem.Driver saveDriver(String name, double x, double y, String plate) {
        return driverRepository.save(new TaxiDispatchSystem.Driver(name,
                new TaxiDispatchSystem.Point(x, y), "Toyota", plate, 4.8));
    }

    private TaxiDispatchSystem.RideOrder saveOrder(double x, double y, TaxiDispatchSystem.Tariff tariff) {
        TaxiDispatchSystem.RideOrder order = new TaxiDispatchSystem.RideOrder(passenger,
                new TaxiDispatchSystem.Point(x, y), new TaxiDispatchSystem.Point(x + 5, y + 2), tariff);
        return taxiDao.saveOrder(order);
    }

    @Nested
    class ModuleOneDriversAndCars {
        @Test
        void calculatesDistanceBetweenCoordinates() {
            var first = new TaxiDispatchSystem.Point(0, 0);
            var second = new TaxiDispatchSystem.Point(3, 4);
            assertEquals(5.0, first.distanceTo(second), 0.0001);
        }

        @Test
        void driverCanBeReservedOnlyOnce() {
            var driver = new TaxiDispatchSystem.Driver("Арман",
                    new TaxiDispatchSystem.Point(1, 1), "Kia", "001 AAA", 4.9);
            assertTrue(driver.tryReserve());
            assertFalse(driver.tryReserve());
            assertEquals(TaxiDispatchSystem.DriverStatus.BUSY, driver.status());
        }

        @Test
        void genericRepositorySavesAndReadsDriver() {
            var saved = saveDriver("Дана", 2, 3, "002 BBB");
            Optional<TaxiDispatchSystem.Driver> found = driverRepository.findById(saved.id());
            assertTrue(found.isPresent());
            assertEquals("Дана", found.get().name());
        }
    }

    @Nested
    class ModuleTwoOrdersAndDispatching {
        @Test
        void nearestAvailableDriverGetsOrder() {
            var far = saveDriver("Дальний", 20, 20, "003 CCC");
            var near = saveDriver("Ближний", 2, 2, "004 DDD");
            var order = saveOrder(1, 1, TaxiDispatchSystem.Tariff.ECONOMY);
            var dispatcher = new TaxiDispatchSystem.DispatcherService(
                    List.of(far, near), taxiDao, new TaxiDispatchSystem.StandardPricing());

            var trip = dispatcher.assignNow(order).orElseThrow();

            assertEquals(near.id(), trip.driver().id());
            assertEquals(TaxiDispatchSystem.OrderStatus.ASSIGNED, order.status());
        }

        @Test
        void oneDriverDoesNotReceiveTwoOrdersAtOnce() throws Exception {
            var onlyDriver = saveDriver("Один", 1, 1, "005 EEE");
            var first = saveOrder(1, 1, TaxiDispatchSystem.Tariff.ECONOMY);
            var second = saveOrder(1, 1, TaxiDispatchSystem.Tariff.ECONOMY);
            var dispatcher = new TaxiDispatchSystem.DispatcherService(
                    List.of(onlyDriver), taxiDao, new TaxiDispatchSystem.StandardPricing());

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successfulAssignments = new AtomicInteger();
            pool.submit(() -> runAssignment(start, dispatcher, first, successfulAssignments));
            pool.submit(() -> runAssignment(start, dispatcher, second, successfulAssignments));
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

            assertEquals(1, successfulAssignments.get());
            assertEquals(1, dispatcher.activeDriverIds().size());
        }

        private void runAssignment(CountDownLatch start,
                                   TaxiDispatchSystem.DispatcherService dispatcher,
                                   TaxiDispatchSystem.RideOrder order,
                                   AtomicInteger counter) {
            try {
                start.await();
                if (dispatcher.assignNow(order).isPresent()) counter.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Test
        void completingTripMakesDriverAvailableAgain() {
            var driver = saveDriver("Олжас", 1, 1, "006 FFF");
            var order = saveOrder(1, 1, TaxiDispatchSystem.Tariff.COMFORT);
            var dispatcher = new TaxiDispatchSystem.DispatcherService(
                    List.of(driver), taxiDao, new TaxiDispatchSystem.StandardPricing());
            var trip = dispatcher.assignNow(order).orElseThrow();

            dispatcher.completeTrip(trip);

            assertEquals(TaxiDispatchSystem.DriverStatus.AVAILABLE, driver.status());
            assertEquals(TaxiDispatchSystem.OrderStatus.COMPLETED, order.status());
            assertEquals(TaxiDispatchSystem.TripStatus.COMPLETED, trip.status());
        }
    }

    @Nested
    class ModuleThreePricingAndReports {
        @Test
        void standardPriceUsesTariffDistanceAndTime() {
            var order = new TaxiDispatchSystem.RideOrder(passenger,
                    new TaxiDispatchSystem.Point(0, 0), new TaxiDispatchSystem.Point(3, 4),
                    TaxiDispatchSystem.Tariff.ECONOMY);
            BigDecimal price = new TaxiDispatchSystem.StandardPricing().calculate(order, 10);
            assertEquals(new BigDecimal("1250.00"), price);
        }

        @Test
        void surgePriceMultipliesStandardPrice() {
            var order = new TaxiDispatchSystem.RideOrder(passenger,
                    new TaxiDispatchSystem.Point(0, 0), new TaxiDispatchSystem.Point(3, 4),
                    TaxiDispatchSystem.Tariff.ECONOMY);
            var pricing = new TaxiDispatchSystem.SurgePricing(
                    new TaxiDispatchSystem.StandardPricing(), new BigDecimal("1.50"));
            assertEquals(new BigDecimal("1875.00"), pricing.calculate(order, 10));
        }

        @Test
        void streamApiGroupsTripsByTariff() {
            var driver = saveDriver("Есеп", 1, 1, "007 GGG");
            var economy = new TaxiDispatchSystem.RideOrder(passenger,
                    new TaxiDispatchSystem.Point(0, 0), new TaxiDispatchSystem.Point(1, 1),
                    TaxiDispatchSystem.Tariff.ECONOMY);
            var comfort = new TaxiDispatchSystem.RideOrder(passenger,
                    new TaxiDispatchSystem.Point(0, 0), new TaxiDispatchSystem.Point(2, 2),
                    TaxiDispatchSystem.Tariff.COMFORT);
            List<TaxiDispatchSystem.Trip> trips = List.of(
                    new TaxiDispatchSystem.Trip(economy, driver, BigDecimal.TEN),
                    new TaxiDispatchSystem.Trip(economy, driver, BigDecimal.TEN),
                    new TaxiDispatchSystem.Trip(comfort, driver, BigDecimal.TEN));

            Map<TaxiDispatchSystem.Tariff, Long> grouped =
                    new TaxiDispatchSystem.ReportService().countByTariff(trips);

            assertEquals(2L, grouped.get(TaxiDispatchSystem.Tariff.ECONOMY));
            assertEquals(1L, grouped.get(TaxiDispatchSystem.Tariff.COMFORT));
        }

        @Test
        void exportsCsvReport() throws Exception {
            var driver = saveDriver("CSV", 1, 1, "008 HHH");
            var order = new TaxiDispatchSystem.RideOrder(passenger,
                    new TaxiDispatchSystem.Point(0, 0), new TaxiDispatchSystem.Point(1, 1),
                    TaxiDispatchSystem.Tariff.COMFORT);
            var trip = new TaxiDispatchSystem.Trip(order, driver, new BigDecimal("1500.00"));
            Path csv = tempDir.resolve("trips.csv");

            new TaxiDispatchSystem.ReportService().exportCsv(List.of(trip), csv);

            assertTrue(Files.exists(csv));
            assertTrue(Files.readString(csv).contains("COMFORT,1500.00"));
        }
    }
}
