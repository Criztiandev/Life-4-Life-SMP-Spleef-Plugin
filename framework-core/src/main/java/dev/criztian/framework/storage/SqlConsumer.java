package dev.criztian.framework.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** A unit of SQL work with no return value. */
@FunctionalInterface
public interface SqlConsumer<C extends Connection> {
    void accept(C connection) throws SQLException;
}
