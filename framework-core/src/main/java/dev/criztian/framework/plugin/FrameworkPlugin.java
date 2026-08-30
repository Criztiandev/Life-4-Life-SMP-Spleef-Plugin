package dev.criztian.framework.plugin;

import dev.criztian.framework.command.CommandService;
import dev.criztian.framework.config.ConfigService;
import dev.criztian.framework.gui.GuiService;
import dev.criztian.framework.integration.papi.PlaceholderService;
import dev.criztian.framework.integration.vault.EconomyService;
import dev.criztian.framework.message.MessageService;
import dev.criztian.framework.metrics.MetricsService;
import dev.criztian.framework.scheduler.SchedulerService;
import dev.criztian.framework.storage.StorageService;
import dev.criztian.framework.storage.StorageSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/**
 * Base class for all framework plugins. Subclasses implement {@link #enable()}
 * (called after all services are up) and optionally {@link #disable()}.
 *
 * <pre>{@code
 * public final class MyPlugin extends FrameworkPlugin {
 *     @Override protected void enable() {
 *         var cfg = configs().config(MyConfig.class, "config.yml");
 *         commands().register(new MyCommands(this));
 *     }
 * }
 * }</pre>
 */
public abstract class FrameworkPlugin extends JavaPlugin {

    private final List<Service> services = new ArrayList<>();

    private @Nullable SchedulerService scheduler;
    private @Nullable ConfigService configs;
    private @Nullable MessageService messages;
    private @Nullable CommandService commands;
    private @Nullable GuiService gui;
    private @Nullable PlaceholderService placeholders;
    private @Nullable EconomyService economy;
    private @Nullable MetricsService metrics;
    private @Nullable StorageService storage;

    /** Plugin logic that runs once all framework services are available. */
    protected abstract void enable();

    /** Plugin logic that runs before framework services shut down. */
    protected void disable() {}

    /** Override to enable bStats with your plugin's own bStats id. */
    protected OptionalInt bstatsId() {
        return OptionalInt.empty();
    }

    @Override
    public final void onEnable() {
        try {
            scheduler = add(new SchedulerService(this));
            configs = add(new ConfigService(this));
            messages = add(new MessageService(this));
            commands = add(new CommandService(this));
            gui = add(new GuiService(this));
            placeholders = add(new PlaceholderService(this));
            economy = add(new EconomyService(this));
            OptionalInt id = bstatsId();
            if (id.isPresent()) {
                metrics = add(new MetricsService(this, id.getAsInt()));
            }

            for (Service service : List.copyOf(services)) {
                service.start();
            }

            enable();
        } catch (Throwable t) {
            getSLF4JLogger().error("Plugin enable failed — disabling", t);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public final void onDisable() {
        // Ordering matters: the plugin flushes while everything still works,
        // then we stop scheduling new work, and only then tear services down —
        // with storage closed last so in-flight saves can still land.
        try {
            disable();
        } catch (Throwable t) {
            getSLF4JLogger().error("Error in plugin disable()", t);
        }

        shutdownQuietly(scheduler);
        for (int i = services.size() - 1; i >= 0; i--) {
            Service service = services.get(i);
            if (service == scheduler || service == storage) {
                continue;
            }
            shutdownQuietly(service);
        }
        shutdownQuietly(storage);

        services.clear();
        scheduler = null;
        configs = null;
        messages = null;
        commands = null;
        gui = null;
        placeholders = null;
        economy = null;
        metrics = null;
        storage = null;
    }

    /**
     * Opt-in storage bootstrap — call from {@link #enable()} with settings from
     * your config. The pool is closed automatically on disable.
     */
    protected final StorageService initStorage(StorageSettings settings) {
        if (storage != null) {
            throw new IllegalStateException("Storage already initialized");
        }
        StorageService created = StorageService.create(this, scheduler(), settings);
        storage = add(created);
        return created;
    }

    // --- service accessors ---

    public final SchedulerService scheduler() {
        return require(scheduler, "scheduler");
    }

    public final ConfigService configs() {
        return require(configs, "configs");
    }

    public final MessageService messages() {
        return require(messages, "messages");
    }

    public final CommandService commands() {
        return require(commands, "commands");
    }

    public final GuiService gui() {
        return require(gui, "gui");
    }

    public final PlaceholderService placeholders() {
        return require(placeholders, "placeholders");
    }

    public final EconomyService economy() {
        return require(economy, "economy");
    }

    /** Present only when {@link #bstatsId()} is overridden. */
    public final @Nullable MetricsService metrics() {
        return metrics;
    }

    /** Present only after {@link #initStorage(StorageSettings)} was called. */
    public final @Nullable StorageService storage() {
        return storage;
    }

    private <S extends Service> S add(S service) {
        services.add(service);
        return service;
    }

    private void shutdownQuietly(@Nullable Service service) {
        if (service == null) {
            return;
        }
        try {
            service.shutdown();
        } catch (Throwable t) {
            getSLF4JLogger().error("Error shutting down {}", service.getClass().getSimpleName(), t);
        }
    }

    private static <S> S require(@Nullable S service, String name) {
        if (service == null) {
            throw new IllegalStateException(
                    "Service '" + name + "' is not available before onEnable/after onDisable");
        }
        return service;
    }
}
