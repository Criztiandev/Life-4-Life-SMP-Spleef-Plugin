package dev.criztian.spleef.player;

import dev.criztian.framework.message.MessageService;
import dev.criztian.spleef.SpleefConfig;
import java.time.Duration;
import java.util.Locale;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/** The boss-bar timer and the full-screen titles. */
public final class Hud {

    private static final Title.Times FLASH = Title.Times.times(
            Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(400));

    /**
     * Long stay, refreshed on a timer — the "Frozen" message has to sit on
     * screen for as long as players are held, not flash once.
     */
    private static final Title.Times HOLD = Title.Times.times(
            Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofMillis(200));

    private final MessageService messages;
    private @Nullable BossBar bar;

    public Hud(MessageService messages) {
        this.messages = messages;
    }

    // --- boss bar ---

    public BossBar createBar(SpleefConfig config, int totalSeconds) {
        destroyBar();
        BossBar created = BossBar.bossBar(
                messages.msg("bossbar.armed", time(totalSeconds)),
                BossBar.MAX_PROGRESS,
                color(config.bossbar.color, BossBar.Color.GREEN),
                BossBar.Overlay.PROGRESS);
        this.bar = created;
        return created;
    }

    public void showTo(Audience audience) {
        if (bar != null) {
            audience.showBossBar(bar);
        }
    }

    public void hideFrom(Audience audience) {
        if (bar != null) {
            audience.hideBossBar(bar);
        }
    }

    /** Repoints the bar at the remaining time. Progress is clamped — a late tick must not throw. */
    public void updateTimer(SpleefConfig config, int remainingSeconds, int totalSeconds) {
        if (bar == null) {
            return;
        }
        float fraction = totalSeconds <= 0 ? 0f : (float) remainingSeconds / totalSeconds;
        float progress = Math.max(BossBar.MIN_PROGRESS, Math.min(BossBar.MAX_PROGRESS, fraction));
        bar.progress(progress);
        bar.name(messages.msg("bossbar.running", time(remainingSeconds)));

        BossBar.Color target;
        if (fraction <= config.bossbar.urgentAt) {
            target = color(config.bossbar.urgentColor, BossBar.Color.RED);
        } else if (fraction <= config.bossbar.warnAt) {
            target = color(config.bossbar.warnColor, BossBar.Color.YELLOW);
        } else {
            target = color(config.bossbar.color, BossBar.Color.GREEN);
        }
        if (bar.color() != target) {
            bar.color(target);
        }
    }

    public void resultBar(Component name) {
        if (bar == null) {
            return;
        }
        bar.progress(BossBar.MAX_PROGRESS);
        bar.color(BossBar.Color.WHITE);
        bar.name(name);
    }

    public void destroyBar() {
        bar = null;
    }

    public @Nullable BossBar bar() {
        return bar;
    }

    // --- titles ---

    public void frozen(Audience audience) {
        audience.showTitle(Title.title(
                messages.msg("title.frozen"), messages.msg("title.frozen-sub"), HOLD));
    }

    public void round(Audience audience, int round) {
        var placeholder = Placeholder.unparsed("round", String.valueOf(round));
        audience.showTitle(Title.title(
                messages.msg("title.round", placeholder),
                messages.msg("title.round-sub", placeholder), FLASH));
    }

    public void winner(Audience audience, String player) {
        var placeholder = Placeholder.unparsed("player", player);
        audience.showTitle(Title.title(
                messages.msg("title.winner", placeholder),
                messages.msg("title.winner-sub", placeholder), FLASH));
    }

    public void draw(Audience audience) {
        audience.showTitle(Title.title(
                messages.msg("title.draw"), messages.msg("title.draw-sub"), FLASH));
    }

    public void eliminated(Player player) {
        player.showTitle(Title.title(
                messages.msg("title.eliminated"), messages.msg("title.eliminated-sub"), FLASH));
    }

    // --- helpers ---

    private static net.kyori.adventure.text.minimessage.tag.resolver.TagResolver time(int seconds) {
        return Placeholder.unparsed("time", format(seconds));
    }

    public static String format(int seconds) {
        int safe = Math.max(0, seconds);
        return String.format(Locale.ROOT, "%d:%02d", safe / 60, safe % 60);
    }

    private static BossBar.Color color(String name, BossBar.Color fallback) {
        try {
            return BossBar.Color.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
