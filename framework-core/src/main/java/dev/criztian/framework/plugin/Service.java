package dev.criztian.framework.plugin;

/**
 * A framework service owned by a {@link FrameworkPlugin}. Services are
 * instance-scoped to their plugin — never static — so that per-plugin
 * relocation of the framework stays correct.
 */
public interface Service {

    /** Called once during plugin enable, in service registration order. */
    default void start() {}

    /** Called once during plugin disable, in reverse registration order. */
    default void shutdown() {}
}
