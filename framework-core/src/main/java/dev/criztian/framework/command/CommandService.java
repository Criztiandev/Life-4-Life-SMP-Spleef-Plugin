package dev.criztian.framework.command;

import dev.criztian.framework.plugin.Service;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.annotations.AnnotationParser;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.minecraft.extras.MinecraftExceptionHandler;
import org.incendo.cloud.paper.PaperCommandManager;
import org.incendo.cloud.paper.util.sender.PaperSimpleSenderMapper;
import org.incendo.cloud.paper.util.sender.Source;

/**
 * Commands via Incendo Cloud v2 on Paper's native Brigadier command API —
 * client-side completions/validation come for free, no {@code commands:}
 * section in plugin.yml, survives reloads via Paper's lifecycle events.
 *
 * <p>Write command containers with {@code @Command}/{@code @Argument}/
 * {@code @Suggestions} (sender type {@link Source}; use
 * {@code PlayerSource} parameters for player-only commands) and pass instances
 * to {@link #register(Object...)}.</p>
 */
public final class CommandService implements Service {

    private final PaperCommandManager<Source> manager;
    private final AnnotationParser<Source> annotationParser;

    public CommandService(JavaPlugin plugin) {
        // simpleCoordinator: handlers run on the calling (region tick) thread —
        // the safe default under Folia. Hop via SchedulerService for I/O.
        this.manager = PaperCommandManager.builder(PaperSimpleSenderMapper.simpleSenderMapper())
                .executionCoordinator(ExecutionCoordinator.<Source>simpleCoordinator())
                .buildOnEnable(plugin);

        // Pretty defaults for syntax errors, missing permissions, parse failures.
        MinecraftExceptionHandler.<Source>create(Source::source)
                .defaultHandlers()
                .registerTo(manager);

        this.annotationParser = new AnnotationParser<>(manager, Source.class);
    }

    public PaperCommandManager<Source> manager() {
        return manager;
    }

    public AnnotationParser<Source> annotationParser() {
        return annotationParser;
    }

    /** Parses and registers annotated command container instances. */
    public void register(Object... annotatedContainers) {
        annotationParser.parse(annotatedContainers);
    }
}
