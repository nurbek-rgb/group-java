package kz.taxi.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kz.taxi.model.*;

public final class ReportService {
    public record DriverSummary(long driverId, String name, long trips, BigDecimal income, double averageRating) {}
    public record Report(LocalDate date, List<DriverSummary> drivers, Map<String, Long> tripsByTariff) {}
    private final DispatchService service;
    public ReportService(DispatchService service) { this.service = service; }

    public Report daily(LocalDate date) {
        List<Trip> completed = service.trips().stream().filter(t -> t.status() == TripStatus.COMPLETED)
                .filter(t -> t.completedAt().atZone(ZoneOffset.UTC).toLocalDate().equals(date)).toList();
        Set<Long> orderIds = completed.stream().map(Trip::orderId).collect(Collectors.toSet());
        Map<String, Long> byTariff = service.orders().stream().filter(o -> orderIds.contains(o.id()))
                .collect(Collectors.groupingBy(RideOrder::tariff, Collectors.counting()));
        List<DriverSummary> summaries = service.drivers().stream().map(driver -> {
            List<Trip> own = completed.stream().filter(t -> t.driverId() == driver.id()).toList();
            BigDecimal income = own.stream().map(Trip::price).reduce(new BigDecimal("0.00"), BigDecimal::add);
            double average = own.stream().filter(t -> t.rating() != null).mapToInt(Trip::rating).average().orElse(0);
            return new DriverSummary(driver.id(), driver.name(), own.size(), income, average);
        }).toList();
        return new Report(date, summaries, Map.copyOf(byTariff));
    }

    public void exportCsv(Path file, LocalDate date) throws IOException {
        Report report = daily(date);
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("date_utc,driver_id,driver_name,trips,income_kzt,average_rating\n");
            for (DriverSummary driver : report.drivers()) {
                writer.write(date + "," + driver.driverId() + "," + csv(driver.name()) + "," + driver.trips() + ","
                        + driver.income().toPlainString() + "," + driver.averageRating() + "\n");
            }
        }
    }
    private static String csv(String text) {
        // Protect spreadsheet formula cells as well as delimiters and quotes.
        String safe = "=+-@\t\r\n".indexOf(text.charAt(0)) >= 0 ? "'" + text : text;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
