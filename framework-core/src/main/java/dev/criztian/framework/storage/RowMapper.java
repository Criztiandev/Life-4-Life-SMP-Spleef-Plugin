package dev.criztian.framework.storage;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Maps the current row of a {@link ResultSet} to a value. */
@FunctionalInterface
public interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
}
