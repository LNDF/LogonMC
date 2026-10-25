package dev.lndf.logonmc.db;

public enum Dialect {
    H2("org.h2.Driver"),
    SQLITE("org.sqlite.JDBC"),
    MYSQL("com.mysql.cj.jdbc.Driver");

    private final String driverClass;

    Dialect(String driverClass) {
        this.driverClass = driverClass;
    }

    public String driverClass() {
        return driverClass;
    }
}
