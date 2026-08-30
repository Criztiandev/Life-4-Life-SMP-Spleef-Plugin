package dev.criztian.spleef.command;

import dev.criztian.spleef.SpleefPlugin;
import dev.criztian.spleef.game.GameState;
import java.util.List;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.incendo.cloud.annotations.Argument;
import org.incendo.cloud.annotations.Command;
import org.incendo.cloud.annotations.CommandDescription;
import org.incendo.cloud.annotations.Permission;
import org.incendo.cloud.annotations.suggestion.Suggestions;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.context.CommandInput;
import org.incendo.cloud.paper.util.sender.PlayerSource;
import org.incendo.cloud.paper.util.sender.Source;

/**
 * Signups. Both routes are supported deliberately: players can add themselves
 * while signups are open, and the operator can enrol the whole server at once.
 */
public final class RosterCommands {

    private final SpleefPlugin plugin;

    public RosterCommands(SpleefPlugin plugin) {
        this.plugin = plugin;
    }

    // --- player-facing ---

    @Command("spleef join")
    @CommandDescription("Sign up for the next spleef event")
    @Permission("spleef.play")
    public void join(PlayerSource source) {
        Player player = source.source();
        if (!plugin.roster().signupsOpen()) {
            plugin.messages().send(player, "roster.closed");
            return;
        }
        if (plugin.games().state() != GameState.IDLE) {
            plugin.messages().send(player, "roster.in-progress");
            return;
        }
        if (!plugin.roster().add(player.getUniqueId())) {
            plugin.messages().send(player, "roster.already-in");
            return;
        }
        plugin.messages().send(player, "roster.joined",
                Placeholder.unparsed("count", String.valueOf(plugin.roster().size())));
    }

    @Command("spleef leave")
    @CommandDescription("Withdraw from the next spleef event")
    @Permission("spleef.play")
    public void leave(PlayerSource source) {
        Player player = source.source();
        if (plugin.games().state() != GameState.IDLE) {
            plugin.messages().send(player, "roster.in-progress");
            return;
        }
        plugin.messages().send(player,
                plugin.roster().remove(player.getUniqueId()) ? "roster.left" : "roster.not-in");
    }

    // --- operator-facing ---

    @Command("spleef roster open")
    @CommandDescription("Open signups so players can /spleef join")
    @Permission("spleef.admin")
    public void open(Source source) {
        plugin.roster().signupsOpen(true);
        plugin.messages().send(source.source(), "roster.opened");
        plugin.messages().send(everyone(), "roster.broadcast-open");
    }

    @Command("spleef roster close")
    @CommandDescription("Close signups")
    @Permission("spleef.admin")
    public void close(Source source) {
        plugin.roster().signupsOpen(false);
        plugin.messages().send(source.source(), "roster.closed-now");
    }

    @Command("spleef roster all")
    @CommandDescription("Enrol every online player without spleef.bypass")
    @Permission("spleef.admin")
    public void all(Source source) {
        int added = plugin.roster().addAllOnline();
        plugin.messages().send(source.source(), "roster.added-all",
                Placeholder.unparsed("added", String.valueOf(added)),
                Placeholder.unparsed("count", String.valueOf(plugin.roster().size())));
    }

    @Command("spleef roster add <player>")
    @CommandDescription("Add one player to the roster")
    @Permission("spleef.admin")
    public void add(Source source, @Argument(value = "player", suggestions = "players")
                    String playerName) {
        Audience audience = source.source();
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            plugin.messages().send(audience, "player-not-found",
                    Placeholder.unparsed("name", playerName));
            return;
        }
        plugin.messages().send(audience,
                plugin.roster().add(target.getUniqueId()) ? "roster.added" : "roster.already-in-other",
                Placeholder.unparsed("player", target.getName()),
                Placeholder.unparsed("count", String.valueOf(plugin.roster().size())));
    }

    @Command("spleef roster remove <player>")
    @CommandDescription("Remove one player from the roster")
    @Permission("spleef.admin")
    public void remove(Source source, @Argument(value = "player", suggestions = "roster")
                       String playerName) {
        Audience audience = source.source();
        OfflinePlayer target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            target = Bukkit.getOfflinePlayerIfCached(playerName);
        }
        if (target == null) {
            plugin.messages().send(audience, "player-not-found",
                    Placeholder.unparsed("name", playerName));
            return;
        }
        plugin.messages().send(audience,
                plugin.roster().remove(target.getUniqueId()) ? "roster.removed" : "roster.not-in-other",
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("count", String.valueOf(plugin.roster().size())));
    }

    @Command("spleef roster clear")
    @CommandDescription("Empty the roster")
    @Permission("spleef.admin")
    public void clear(Source source) {
        plugin.roster().clear();
        plugin.messages().send(source.source(), "roster.cleared");
    }

    @Command("spleef roster list")
    @CommandDescription("Show who is signed up")
    @Permission("spleef.admin")
    public void list(Source source) {
        Audience audience = source.source();
        List<String> names = plugin.roster().names();
        if (names.isEmpty()) {
            plugin.messages().send(audience, "roster.none");
            return;
        }
        plugin.messages().send(audience, "roster.list",
                Placeholder.unparsed("count", String.valueOf(names.size())),
                Placeholder.unparsed("names", String.join(", ", names)),
                Placeholder.unparsed("signups", plugin.roster().signupsOpen() ? "open" : "closed"));
    }

    @Suggestions("players")
    public List<String> onlineNames(CommandContext<Source> context, CommandInput input) {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    @Suggestions("roster")
    public List<String> rosterNames(CommandContext<Source> context, CommandInput input) {
        return plugin.roster().names();
    }

    private Audience everyone() {
        return Audience.audience(Bukkit.getOnlinePlayers());
    }
}
