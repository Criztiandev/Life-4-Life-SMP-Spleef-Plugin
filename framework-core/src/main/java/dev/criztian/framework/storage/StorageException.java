package dev.criztian.framework.storage;

/** Unchecked wrapper for {@link java.sql.SQLException}s from {@link StorageService}. */
public final class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
