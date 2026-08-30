package dev.criztian.framework.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.Nullable;

/** Cancellable handle for a task scheduled through {@link SchedulerService}. */
public final class TaskHandle {

    /** Returned when scheduling was impossible (e.g. the target entity was already removed). */
    static final TaskHandle NOOP = new TaskHandle(null);

    private final @Nullable ScheduledTask task;

    TaskHandle(@Nullable ScheduledTask task) {
        this.task = task;
    }

    public void cancel() {
        if (task != null) {
            task.cancel();
        }
    }

    public boolean cancelled() {
        return task == null || task.isCancelled();
    }
}
