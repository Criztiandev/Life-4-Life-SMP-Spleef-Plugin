package dev.criztian.framework.storage;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/**
 * Database connection settings, designed to be embedded in a plugin's config
 * class as a section: {@code public StorageSettings storage = new StorageSettings();}
 */
@ConfigSerializable
public final class StorageSettings {

    public enum Type { SQLITE, MARIADB }

    @Comment("SQLITE (file in the plugin folder, zero setup) or MARIADB (external server, also speaks MySQL)")
    public Type type = Type.SQLITE;

    @Comment("SQLite database file name, relative to the plugin's data folder")
    public String file = "data.db";

    @Comment("MariaDB/MySQL connection details (ignored for SQLITE)")
    public String host = "localhost";
    public int port = 3306;
    public String database = "minecraft";
    public String username = "";
    public String password = "";

    @Comment("Connection pool size (ignored for SQLITE, which is always 1)")
    public int poolSize = 8;

    public StorageSettings() {}

    public static StorageSettings sqlite(String file) {
        StorageSettings settings = new StorageSettings();
        settings.type = Type.SQLITE;
        settings.file = file;
        return settings;
    }
}
