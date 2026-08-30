package dev.criztian.framework.storage;

import java.util.List;

/**
 * One versioned schema migration. Versions start at 1 and only ever grow;
 * applied versions are recorded in the {@code schema_version} table.
 */
public record Migration(int version, String description, List<String> statements) {

    public static Migration of(int version, String description, String... sql) {
        return new Migration(version, description, List.of(sql));
    }
}
