package kz.taxi;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Scanner;
import java.util.Set;
import java.util.UUID;
import kz.taxi.model.*;
import kz.taxi.repository.Database;
import kz.taxi.service.*;

public final class Main {
    private Main() {}
    public static void main(String[] args) {
        try { run(args); }
        catch (Exception e) { System.err.println("Қате: " + e.getMessage()); System.exit(1); }
    }

    private static void run(String[] args) throws Exception {
        boolean demo = false;
        Path dbPath = null;
        Path report = Path.of("reports", "daily.csv");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--demo" -> demo = true;
                case "--db" -> { if (++i == args.length) throw new IllegalArgumentException("--db үшін файл жолы қажет"); dbPath = Path.of(args[i]); }
                case "--report" -> { if (++i == args.length) throw new IllegalArgumentException("--report үшін файл жолы қажет"); report = Path.of(args[i]); }
                case "--help" -> { System.out.println("java -jar target/taxi-dispatch.jar [--demo] [--db file.db] [--report report.csv]"); return; }
                default -> throw new IllegalArgumentException("Белгісіз параметр: " + args[i]);
            }
        }
        if (dbPath == null) dbPath = Path.of("data", demo ? "demo.db" : "taxi.db");
        try (Database db = new Database(dbPath)) {
            DispatchService service = new DispatchService(db);
            try (OrderDispatcher dispatcher = new OrderDispatcher(service, 3)) {
                System.out.println("Такси диспетчерлік жүйесі | Дерекқор: " + dbPath.toAbsolutePath());
                if (demo) demo(service, dispatcher, report);
                else console(service, dispatcher, report);
            }
        }
    }

    private static void demo(DispatchService service, OrderDispatcher dispatcher, Path report) throws Exception {
        String batch = UUID.randomUUID().toString().substring(0, 8);
        Passenger passenger = service.addPassenger("Демо жолаушы");
        service.addDriver("Айбек", new Location(0, 0), new Vehicle("Hyundai Accent", "A-" + batch, "ECONOMY"));
        service.addDriver("Данияр", new Location(4, 2), new Vehicle("Kia Rio", "B-" + batch, "ECONOMY"));
        service.addDriver("Әлия", new Location(2, 1), new Vehicle("Toyota Camry", "C-" + batch, "COMFORT"));
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 4; i++) {
            RideOrder order = service.submit(passenger.id(), new Location(i, 0), new Location(i + 3, 4),
                    i == 3 ? Tariff.COMFORT : Tariff.ECONOMY, new BigDecimal("10"));
            ids.add(order.id()); dispatcher.enqueue(order.id());
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (ids.stream().anyMatch(id -> service.order(id).status() != OrderStatus.COMPLETED)) {
            dispatcher.checkHealth();
            if (System.nanoTime() > deadline) throw new IllegalStateException("Демо уақытында аяқталмады");
            for (Trip trip : service.trips()) {
                if (ids.contains(trip.orderId()) && trip.status() == TripStatus.CREATED) {
                    service.accept(trip.id()); service.start(trip.id()); service.complete(trip.id()); service.rate(trip.id(), 5);
                    System.out.printf("Сапар #%d: жүргізуші #%d, %s ₸ — COMPLETED%n", trip.id(), trip.driverId(), trip.price());
                }
            }
            Thread.sleep(20);
        }
        export(service, report);
        System.out.println("Демо сәтті аяқталды: 4 сапар, жүргізушілер қайтадан бос.");
    }

    private static void console(DispatchService service, OrderDispatcher dispatcher, Path report) {
        Scanner in = new Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("Оқу симуляциясы: координаталар — км, баға — теңге. Қабылдау уақыты — 30 секунд.");
        while (true) {
            dispatcher.checkHealth();
            System.out.println("""

                    1 — Тізімдер             2 — Жүргізуші қосу     3 — Жолаушы қосу
                    4 — Тапсырыс беру        5 — Сапарды қабылдау  6 — Сапарды бастау
                    7 — Сапарды аяқтау       8 — Тапсырысты жою    9 — Рейтинг қою
                    10 — Онлайн/офлайн       11 — CSV есеп         0 — Шығу
                    """);
            System.out.print("> ");
            if (!in.hasNextLine()) return;
            String choice = in.nextLine().strip();
            try {
                switch (choice) {
                    case "0" -> { return; }
                    case "1" -> list(service);
                    case "2" -> {
                        String name = ask(in, "Аты");
                        Location location = location(in, "Жүргізуші");
                        Vehicle vehicle = new Vehicle(ask(in, "Көлік моделі"), ask(in, "Көлік нөмірі"), tariff(in).name());
                        System.out.println("Жүргізуші ID: " + service.addDriver(name, location, vehicle).id());
                    }
                    case "3" -> System.out.println("Жолаушы ID: " + service.addPassenger(ask(in, "Аты")).id());
                    case "4" -> {
                        long passenger = number(in, "Жолаушы ID");
                        Location from = location(in, "Бастапқы"), to = location(in, "Межелі");
                        Tariff tariff = tariff(in);
                        RideOrder order = service.submit(passenger, from, to, tariff, new BigDecimal(ask(in, "Болжамды минут")));
                        dispatcher.enqueue(order.id());
                        System.out.printf("Тапсырыс #%d, баға %s ₸, surge x%s%n", order.id(), order.price(), order.surge());
                    }
                    case "5" -> service.accept(number(in, "Сапар ID"));
                    case "6" -> service.start(number(in, "Сапар ID"));
                    case "7" -> service.complete(number(in, "Сапар ID"));
                    case "8" -> service.cancel(number(in, "Тапсырыс ID"));
                    case "9" -> service.rate(number(in, "Сапар ID"), Integer.parseInt(ask(in, "Баға (1–5)")));
                    case "10" -> {
                        long driver = number(in, "Жүргізуші ID");
                        String status = ask(in, "1 — онлайн, 0 — офлайн");
                        if (!status.equals("1") && !status.equals("0")) throw new IllegalArgumentException("0 немесе 1 енгізіңіз");
                        service.setOnline(driver, status.equals("1"));
                    }
                    case "11" -> export(service, report);
                    default -> System.out.println("Мәзірдегі нөмірді таңдаңыз.");
                }
            } catch (java.util.NoSuchElementException e) { return; }
            catch (IllegalArgumentException | IllegalStateException | java.io.IOException e) {
                System.out.println("Қате: " + e.getMessage());
            }
        }
    }

    private static String ask(Scanner in, String prompt) { System.out.print(prompt + ": "); return in.nextLine().strip(); }
    private static long number(Scanner in, String prompt) { return Long.parseLong(ask(in, prompt)); }
    private static Location location(Scanner in, String prompt) {
        return new Location(Double.parseDouble(ask(in, prompt + " X")), Double.parseDouble(ask(in, prompt + " Y")));
    }
    private static Tariff tariff(Scanner in) {
        return switch (ask(in, "Тариф: 1 — ECONOMY, 2 — COMFORT")) {
            case "1" -> Tariff.ECONOMY;
            case "2" -> Tariff.COMFORT;
            default -> throw new IllegalArgumentException("Тариф үшін 1 немесе 2 енгізіңіз");
        };
    }
    private static void list(DispatchService service) {
        service.passengers().forEach(p -> System.out.printf("Жолаушы #%d: %s%n", p.id(), p.name()));
        service.drivers().forEach(d -> System.out.printf("Жүргізуші #%d: %s, %s, %s, %s%n", d.id(), d.name(), d.vehicle(), d.status(), d.location()));
        service.orders().forEach(o -> System.out.printf("Тапсырыс #%d: %s, %s, %s ₸%n", o.id(), o.tariff(), o.status(), o.price()));
        service.trips().forEach(t -> System.out.printf("Сапар #%d: тапсырыс #%d, жүргізуші #%d, %s, қабылданды=%s%n",
                t.id(), t.orderId(), t.driverId(), t.status(), t.acceptedAt() != null));
    }
    private static void export(DispatchService service, Path file) throws java.io.IOException {
        ReportService reports = new ReportService(service);
        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        reports.exportCsv(file, date);
        System.out.println("Тариф бойынша сапарлар: " + reports.daily(date).tripsByTariff());
        System.out.println("CSV есеп: " + file.toAbsolutePath());
    }
}
