package dev.criztian.framework.metrics;

import dev.criztian.framework.plugin.Service;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.CustomChart;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * bStats metrics. Each consuming plugin registers with its OWN bStats plugin id
 * (override {@code FrameworkPlugin#bstatsId()}); the shaded bStats classes are
 * relocated per plugin, which bStats requires.
 */
public final class MetricsService implements Service {

    private final Metrics metrics;

    public MetricsService(JavaPlugin plugin, int bstatsPluginId) {
        this.metrics = new Metrics(plugin, bstatsPluginId);
    }

    public void chart(CustomChart chart) {
        metrics.addCustomChart(chart);
    }

    @Override
    public void shutdown() {
        metrics.shutdown();
    }
}
