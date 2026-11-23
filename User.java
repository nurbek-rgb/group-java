package kz.taxi.model;

public abstract class User {
    private final long id;
    private final String name;
    protected User(long id, String name) { this.id = Checks.id(id); this.name = Checks.text(name); }
    public final long id() { return id; }
    public final String name() { return name; }
}
