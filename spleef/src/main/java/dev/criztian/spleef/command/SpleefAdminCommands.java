package dev.criztian.spleef.command;

import dev.criztian.spleef.SpleefPlugin;
import dev.criztian.spleef.game.SpleefSession;
import dev.criztian.spleef.player.Hud;
import java.util.List;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.paper.util.sender.Source;
import org.jetbrains.annotations.Nullable;

/** Session control: prepare, start, round, end, plus status and vault recovery. */
public final class SpleefAdminCommands {

    private final SpleefPlugin plugin;

    public SpleefAdminCommands(SpleefPlugin plugin) {
        this.plugin = plugin;
    }

    @Command("spleef prepare [arena]")
    @CommandDescription("Scatter, freeze and equip the roster; hold them until /spleef start")
    @Permission("spleef.admin")
    public void prepare(Source source, @Argument(value = "arena", suggestions = "arenas")
                        @Nullable String arena) {
        plugin.games().prepare(source.source(), arena);
    }

    @Command("spleef start")
    @CommandDescription("Unfreeze everyone and start the round timer")
    @Permission("spleef.admin")
    public void start(Source source) {
        plugin.games().start(source.source(), false);
    }

    @Command("spleef start force")
    @CommandDescription("Start even if fewer than the configured minimum are playing")
    @Permission("spleef.admin")
    public void startForced(Source source) {
        plugin.games().start(source.source(), true);
    }

    @Command("spleef round <number>")
    @CommandDescription("Reset the platform and re-arm everyone for the next round")
    @Permission("spleef.admin")
    public void round(Source source, @Argument("number") int number) {
        if (number < 1) {
            plugin.messages().send(source.source(), "game.bad-round");
            return;
        }
        plugin.games().round(source.source(), number);
    }

    @Command("spleef end")
    @CommandDescription("End the event, restore everyone's items and send them home")
    @Permission("spleef.admin")
    public void end(Source source) {
        plugin.games().end(source.source());
    }

    @Command("spleef status")
    @CommandDescription("Show the current spleef session state")
    @Permission("spleef.admin")
    public void status(Source source) {
        Audience audience = source.source();
        SpleefSession session = plugin.games().session();
        if (session == null) {
            plugin.messages().send(audience, "status.idle",
                    Placeholder.unparsed("arena", activeName()),
                    Placeholder.unparsed("roster", String.valueOf(plugin.roster().size())),
                    Placeholder.unparsed("signups", plugin.roster().signupsOpen() ? "open" : "closed"),
                    Placeholder.unparsed("owed", String.valueOf(plugin.vault().pendingCount())));
            return;
        }
        plugin.messages().send(audience, "status.active",
                Placeholder.unparsed("state", session.state().name()),
                Placeholder.unparsed("arena", session.arena().name()),
                Placeholder.unparsed("round", String.valueOf(session.round())),
                Placeholder.unparsed("alive", String.valueOf(session.alive().size())),
                Placeholder.unparsed("total", String.valueOf(session.participants().size())),
                Placeholder.unparsed("time", Hud.format(session.remainingSeconds())),
                Placeholder.unparsed("owed", String.valueOf(plugin.vault().pendingCount())));
    }

    @Command("spleef vault list")
    @CommandDescription("List players whose items the plugin is still holding")
    @Permission("spleef.admin")
    public void vaultList(Source source) {
        Audience audience = source.source();
        List<String> names = plugin.vault().pendingNames();
        if (names.isEmpty()) {
            plugin.messages().send(audience, "vault.none");
            return;
        }
        plugin.messages().send(audience, "vault.list",
                Placeholder.unparsed("count", String.valueOf(names.size())),
                Placeholder.unparsed("names", String.join(", ", names)));
    }

    @Command("spleef vault restore <player>")
    @CommandDescription("Hand a specific player their vaulted items back right now")
    @Permission("spleef.admin")
    public void vaultRestore(Source source, @Argument(value = "player", suggestions = "vaulted")
                             String playerName) {
        Audience audience = source.source();
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            plugin.messages().send(audience, "player-not-found",
                    Placeholder.unparsed("name", playerName));
            return;
        }
        plugin.loadout().strip(target);
        boolean restored = plugin.vault()
                .restore(target, plugin.config().restoreMode).isPresent();
        plugin.messages().send(audience, restored ? "vault.restored" : "vault.nothing",
                Placeholder.unparsed("player", target.getName()));
    }

    @Command("spleef reload")
    @CommandDescription("Reload config.yml, messages.yml and the arena list")
    @Permission("spleef.admin")
    public void reload(Source source) {
        plugin.reloadAll();
        plugin.messages().send(source.source(), "reloaded");
    }

    @Suggestions("vaulted")
    public List<String> vaultedNames(CommandContext<Source> context, CommandInput input) {
        return plugin.vault().pendingNames();
    }

    private String activeName() {
        String active = plugin.config().activeArena;
        return active == null || active.isBlank() ? "none" : active;
    }
}
