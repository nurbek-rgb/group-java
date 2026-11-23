package kz.taxi.model;

import java.util.Objects;

/** Immutable database snapshot; state changes go through DispatchService. */
public final class Driver extends User {
    private final Location location;
    private final Vehicle vehicle;
    private final DriverStatus status;
    public Driver(long id, String name, Location location, Vehicle vehicle, DriverStatus status) {
        super(id, name);
        this.location = Objects.requireNonNull(location);
        this.vehicle = Objects.requireNonNull(vehicle);
        this.status = Objects.requireNonNull(status);
    }
    public Location location() { return location; }
    public Vehicle vehicle() { return vehicle; }
    public DriverStatus status() { return status; }
}
