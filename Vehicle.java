package kz.taxi.model;

public record Vehicle(String model, String plate, String tariff) {
    public Vehicle { model = Checks.text(model); plate = Checks.text(plate); tariff = Checks.text(tariff); }
}
