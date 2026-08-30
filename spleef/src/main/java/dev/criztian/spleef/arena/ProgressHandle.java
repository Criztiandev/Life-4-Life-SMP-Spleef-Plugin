package dev.criztian.spleef.arena;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A long-running, chunk-batched block operation.
 *
 * <p>Cancellation must be real: {@code /spleef end} has to be able to abort an
 * in-flight arena reset, or the operator's panic button becomes a multi-second
 * wait.</p>
 */
public final class ProgressHandle {

    private final CompletableFuture<Void> future = new CompletableFuture<>();
    private final AtomicInteger done = new AtomicInteger();
    private final int total;
    private volatile boolean cancelled;

    public ProgressHandle(int total) {
        this.total = total;
    }

    public CompletableFuture<Void> future() {
        return future;
    }

    public int done() {
        return done.get();
    }

    public int total() {
        return total;
    }

    public int percent() {
        return total == 0 ? 100 : Math.min(100, done.get() * 100 / total);
    }

    public boolean cancelled() {
        return cancelled;
    }

    /** Requests cancellation; the driver stops on its next tick. */
    public void cancel() {
        cancelled = true;
    }

    // --- driver-facing ---

    public void step() {
        done.incrementAndGet();
    }

    public void complete() {
        future.complete(null);
    }

    public void completeCancelled() {
        future.completeExceptionally(new CancellationException("Operation cancelled"));
    }

    public void fail(Throwable error) {
        future.completeExceptionally(error);
    }
}
