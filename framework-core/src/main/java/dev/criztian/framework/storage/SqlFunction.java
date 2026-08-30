package dev.criztian.framework.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** A unit of SQL work that returns a value. */
@FunctionalInterface
public interface SqlFunction<C extends Connection, T> {
    T apply(C connection) throws SQLException;
}
