package dev.criztian.spleef.game;

import dev.criztian.spleef.SpleefConfig;
import dev.criztian.spleef.SpleefPlugin;
import dev.criztian.spleef.arena.Arena;
import dev.criztian.spleef.arena.Cuboid;
import dev.criztian.spleef.arena.snapshot.BlockSnapshot;
import dev.criztian.spleef.player.ItemVault;
import dev.criztian.spleef.player.VaultRecord;
import dev.criztian.spleef.scatter.Scatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Drives the whole event: prepare, start, round, end.
 *
 * <p>Every command runs on the global region thread and every long operation
 * parks the session in a busy state ({@code PREPARING}, {@code RESETTING},
 * {@code ENDING}) that refuses further input. That one rule removes the whole
 * class of races between an in-flight block job and an impatient operator.</p>
 */
public final class SpleefManager {

    private final SpleefPlugin plugin;
    private @Nullable SpleefSession session;

    public SpleefManager(SpleefPlugin plugin) {
        this.plugin = plugin;
    }

    public @Nullable SpleefSession session() {
        return session;
    }

    public GameState state() {
        return session == null ? GameState.IDLE : session.state();
    }

    public boolean isParticipant(UUID player) {
        return session != null && session.isParticipant(player);
    }

    // ------------------------------------------------------------------
    // prepare
    // ------------------------------------------------------------------

    public void prepare(Audience operator, @Nullable String arenaName) {
        if (state() != GameState.IDLE) {
            reject(operator);
            return;
        }
        Arena arena = resolveArena(operator, arenaName);
        if (arena == null) {
            return;
        }
        World world = arena.region().world();
        if (world == null) {
            plugin.messages().send(operator, "game.world-missing",
                    Placeholder.unparsed("world", arena.region().worldName()));
            return;
        }
        List<Player> players = resolveParticipants(operator);
        if (players == null) {
            return;
        }

        SpleefConfig config = plugin.config();
        String sessionId = UUID.randomUUID().toString();

        plugin.messages().send(operator, "game.preparing",
                Placeholder.unparsed("arena", arena.name()),
                Placeholder.unparsed("count", String.valueOf(players.size())));

        plugin.arenas().pin(arena.region());

        // Load the snapshot and scan the platform BEFORE taking custody of
        // anything. If either fails we abort having touched no inventories.
        plugin.arenas().loadSnapshot(arena.name())
                .thenComposeAsync(snapshot ->
                                Scatter.scan(plugin.scheduler(), world, arena.region(), config)
                                        .result()
                                        .thenApply(report -> new Prepared(snapshot, report)),
                        plugin.scheduler().globalExecutor())
                .thenAcceptAsync(prepared -> beginSession(operator, arena, sessionId, config,
                                prepared, players),
                        plugin.scheduler().globalExecutor())
                .exceptionally(error -> {
                    plugin.scheduler().run(() -> abortPrepare(operator, error));
                    return null;
                });
    }

    private record Prepared(BlockSnapshot snapshot, Scatter.ScanReport scan) {}

    private void beginSession(Audience operator, Arena arena, String sessionId,
                              SpleefConfig config, Prepared prepared, List<Player> players) {
        if (!prepared.scan().usable()) {
            plugin.messages().send(operator, "game.no-spawns",
                    Placeholder.unparsed("arena", arena.name()));
            plugin.arenas().unpinAll();
            session = null;
            return;
        }

        SpleefSession created = new SpleefSession(sessionId, arena, prepared.snapshot(),
                prepared.scan());
        created.state(GameState.PREPARING);
        created.totalSeconds(config.roundSeconds);
        this.session = created;

        plugin.vault().openSession(sessionId, arena.name());

        // Take custody first: items are committed to the vault before anyone is
        // moved, so a failure from here on can never strand a cleared inventory.
        List<Player> enrolled = new ArrayList<>(players.size());
        for (Player player : players) {
            ItemVault.Outcome outcome = plugin.vault().vault(player, sessionId);
            if (outcome == ItemVault.Outcome.FAILED) {
                plugin.messages().send(operator, "game.vault-failed",
                        Placeholder.unparsed("player", player.getName()));
                continue;
            }
            created.add(new Participant(player.getUniqueId(), player.getName()));
            enrolled.add(player);
        }
        if (enrolled.isEmpty()) {
            plugin.messages().send(operator, "game.nobody-enrolled");
            plugin.vault().closeSession(sessionId);
            plugin.arenas().unpinAll();
            session = null;
            return;
        }

        armRound(operator, created, enrolled, 1, false);
    }

    private void abortPrepare(Audience operator, Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        plugin.getSLF4JLogger().error("Prepare failed", cause);
        plugin.messages().send(operator, "game.prepare-failed",
                Placeholder.unparsed("reason", String.valueOf(cause.getMessage())));
        plugin.arenas().unpinAll();
        session = null;
    }

    // ------------------------------------------------------------------
    // arming a round (shared by prepare and round)
    // ------------------------------------------------------------------

    private void armRound(Audience operator, SpleefSession current, List<Player> players,
                          int round, boolean announceRound) {
        SpleefConfig config = plugin.config();
        World world = current.arena().region().world();
        if (world == null) {
            plugin.messages().send(operator, "game.world-missing",
                    Placeholder.unparsed("world", current.arena().region().worldName()));
            return;
        }

        // Seeded by session and round: the same round always scatters the same
        // way, so a disputed spawn can be reproduced.
        long seed = current.id().hashCode() * 31L + round;
        List<Location> spots = Scatter.choose(world, current.arena().region(), current.scan(),
                players.size(), config.scatter.minSpacing, seed);
        if (spots.size() < players.size()) {
            // Only reachable if the cached scan went stale (the arena was rebuilt
            // under a live session). Better to say so than to half-place people.
            plugin.messages().send(operator, "game.no-spawns",
                    Placeholder.unparsed("arena", current.arena().name()));
            current.state(GameState.ROUND_OVER);
            return;
        }

        List<CompletableFuture<Boolean>> teleports = new ArrayList<>(players.size());
        for (int i = 0; i < players.size(); i++) {
            teleports.add(players.get(i).teleportAsync(spots.get(i),
                    PlayerTeleportEvent.TeleportCause.PLUGIN));
        }

        CompletableFuture.allOf(teleports.toArray(CompletableFuture[]::new))
                // The thread a teleport future completes on is not contractual —
                // always hop back before touching game state.
                .whenCompleteAsync((ignored, error) -> {
                    if (error != null) {
                        plugin.getSLF4JLogger().warn("Some scatter teleports failed", error);
                    }
                    finishArming(operator, current, players, round, announceRound, config);
                }, plugin.scheduler().globalExecutor());
    }

    private void finishArming(Audience operator, SpleefSession current, List<Player> players,
                              int round, boolean announceRound, SpleefConfig config) {
        current.round(round);
        current.totalSeconds(config.roundSeconds);
        current.reviveAll();

        for (Player player : players) {
            if (!player.isOnline()) {
                // Quit somewhere between the scatter teleport and this callback.
                continue;
            }
            player.setGameMode(GameMode.SURVIVAL);
            player.setHealth(healthCap(player));
            player.setFoodLevel(20);
            player.setSaturation(20f);
            player.setFireTicks(0);
            plugin.loadout().give(player, config);
            plugin.freeze().freeze(player);
        }

        Audience audience = current.audience();
        if (announceRound) {
            plugin.hud().round(audience, round);
        }
        plugin.hud().frozen(audience);

        // Re-show the frozen title on a timer: it has to sit on screen for as
        // long as players are held, not flash once.
        current.cancelFrozenTitle();
        current.frozenTitleTask(plugin.scheduler().runTimer(() -> {
            if (state() == GameState.ARMED && session != null) {
                plugin.hud().frozen(session.audience());
            }
        }, 60, 60));

        // Hide the previous round's bar before making a new one, or players
        // accumulate a stack of dead bars, one per round.
        BossBar previous = plugin.hud().bar();
        if (previous != null) {
            everyoneAudience().hideBossBar(previous);
        }
        plugin.hud().createBar(config, config.roundSeconds);
        showBarToAudience(config);

        current.state(GameState.ARMED);
        plugin.vault().updateRound(current.id(), round);
        plugin.messages().send(operator, "game.armed",
                Placeholder.unparsed("count", String.valueOf(players.size())),
                Placeholder.unparsed("round", String.valueOf(round)),
                Placeholder.unparsed("time", dev.criztian.spleef.player.Hud.format(
                        config.roundSeconds)));
    }

    // ------------------------------------------------------------------
    // start
    // ------------------------------------------------------------------

    public void start(Audience operator, boolean force) {
        SpleefSession current = session;
        if (current == null || current.state() != GameState.ARMED) {
            reject(operator);
            return;
        }
        SpleefConfig config = plugin.config();
        int alive = current.alive().size();
        if (alive < config.minParticipants && !force) {
            plugin.messages().send(operator, "game.too-few",
                    Placeholder.unparsed("count", String.valueOf(alive)),
                    Placeholder.unparsed("min", String.valueOf(config.minParticipants)));
            return;
        }

        current.cancelFrozenTitle();
        plugin.freeze().thawAll();
        for (Player player : current.onlinePlayers()) {
            player.clearTitle();
        }

        current.startTimer(config.roundSeconds);
        current.state(GameState.RUNNING);
        current.timerTask(plugin.scheduler().runTimer(this::tick, 20, 20));

        plugin.messages().send(current.audience(), "game.go");
        plugin.messages().send(operator, "game.started",
                Placeholder.unparsed("count", String.valueOf(alive)));
    }

    private void tick() {
        SpleefSession current = session;
        if (current == null || current.state() != GameState.RUNNING) {
            return;
        }
        int remaining = current.remainingSeconds();
        plugin.hud().updateTimer(plugin.config(), remaining, current.totalSeconds());
        if (remaining <= 0) {
            timeUp(current);
        }
    }

    // ------------------------------------------------------------------
    // round
    // ------------------------------------------------------------------

    public void round(Audience operator, int number) {
        SpleefSession current = session;
        if (current == null) {
            plugin.messages().send(operator, "game.no-session");
            return;
        }
        if (current.state().busy()) {
            reject(operator);
            return;
        }

        current.cancelAllTasks();
        plugin.freeze().thawAll();
        current.state(GameState.RESETTING);
        plugin.messages().send(operator, "game.resetting",
                Placeholder.unparsed("round", String.valueOf(number)));

        // The restore MUST finish before anyone is scattered back in: a block
        // written into a player's head suffocates them.
        plugin.arenas().restore(current.snapshot())
                .whenCompleteAsync((changed, error) -> {
                    if (error != null) {
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        plugin.getSLF4JLogger().error("Round reset failed", cause);
                        plugin.messages().send(operator, "area.reset-failed",
                                Placeholder.unparsed("reason", String.valueOf(cause.getMessage())));
                        current.state(GameState.ROUND_OVER);
                        return;
                    }
                    plugin.messages().send(operator, "game.reset-done",
                            Placeholder.unparsed("blocks", String.valueOf(changed)));
                    armRound(operator, current, current.onlinePlayers(), number, true);
                }, plugin.scheduler().globalExecutor());
    }

    // ------------------------------------------------------------------
    // end
    // ------------------------------------------------------------------

    public void end(Audience operator) {
        SpleefSession current = session;
        if (current == null) {
            plugin.messages().send(operator, "game.no-session");
            return;
        }

        current.state(GameState.ENDING);
        current.cancelAllTasks();
        plugin.arenas().cancelActive();
        plugin.freeze().thawAll();

        SpleefConfig config = plugin.config();
        hideBarFromEveryone();
        plugin.hud().destroyBar();

        int restored = 0;
        int owed = 0;
        for (Participant participant : current.participants()) {
            Player player = participant.online();
            if (player == null) {
                // Left pending on purpose — the join path hands it back.
                owed++;
                continue;
            }
            player.clearTitle();
            plugin.loadout().strip(player);
            Optional<VaultRecord> record = plugin.vault().restore(player, config.restoreMode);
            restored++;
            Location home = plugin.homes().resolve(player,
                    record.map(VaultRecord::preGame).orElse(null));
            player.teleportAsync(home, PlayerTeleportEvent.TeleportCause.PLUGIN);
            plugin.messages().send(player, "game.sent-home");
        }

        plugin.vault().closeSession(current.id());

        BlockSnapshot snapshot = current.snapshot();
        int finalRestored = restored;
        int finalOwed = owed;
        plugin.arenas().restore(snapshot).whenCompleteAsync((changed, error) -> {
            plugin.arenas().unpinAll();
            session = null;
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                plugin.getSLF4JLogger().error("Final arena restore failed", cause);
            }
            plugin.messages().send(operator, "game.ended",
                    Placeholder.unparsed("restored", String.valueOf(finalRestored)),
                    Placeholder.unparsed("owed", String.valueOf(finalOwed)));
            if (finalOwed > 0) {
                plugin.messages().send(operator, "game.owed-note",
                        Placeholder.unparsed("owed", String.valueOf(finalOwed)));
            }
        }, plugin.scheduler().globalExecutor());
    }

    /**
     * Shutdown path. Runs from {@code disable()} while services are still live.
     *
     * <p>Default policy is HOLD: tasks are cancelled and the session is closed,
     * but items stay vaulted and come back through the join path on next boot.
     * Trying to teleport and re-equip everyone during shutdown is unreliable —
     * writes and teleports may not flush.</p>
     */
    public void shutdown() {
        SpleefSession current = session;
        if (current == null) {
            return;
        }
        current.cancelAllTasks();
        plugin.arenas().cancelActive();
        plugin.freeze().thawAll();
        hideBarFromEveryone();
        plugin.hud().destroyBar();

        if (plugin.config().shutdownPolicy == SpleefConfig.ShutdownPolicy.RESTORE) {
            for (Participant participant : current.participants()) {
                Player player = participant.online();
                if (player != null) {
                    plugin.loadout().strip(player);
                    plugin.vault().restore(player, plugin.config().restoreMode);
                }
            }
            plugin.vault().closeSession(current.id());
        } else {
            plugin.getSLF4JLogger().warn("Server stopping mid-event: {} player(s) still have items "
                            + "vaulted. They will be restored automatically on next login.",
                    current.participants().size());
            // The session row is left open on purpose so the next boot logs the
            // orphan warning and the operator knows an event was interrupted.
        }
        plugin.arenas().unpinAll();
        session = null;
    }

    // ------------------------------------------------------------------
    // game events
    // ------------------------------------------------------------------

    /** Marks a participant out. Called from the death listener, before evaluation. */
    public void eliminate(Player player, boolean announce) {
        SpleefSession current = session;
        if (current == null) {
            return;
        }
        Participant participant = current.participant(player.getUniqueId());
        if (participant == null || participant.eliminated()) {
            return;
        }
        participant.status(ParticipantStatus.ELIMINATED);
        plugin.loadout().strip(player);
        if (announce) {
            plugin.messages().send(current.audience(), "game.eliminated",
                    Placeholder.unparsed("player", player.getName()),
                    Placeholder.unparsed("remaining", String.valueOf(current.alive().size())));
        }
    }

    /** Where eliminated players watch from: above the middle of the arena. */
    public Location spectatorSpot() {
        SpleefSession current = session;
        if (current == null) {
            return plugin.getServer().getWorlds().get(0).getSpawnLocation();
        }
        Cuboid region = current.arena().region();
        World world = region.world();
        if (world == null) {
            return plugin.getServer().getWorlds().get(0).getSpawnLocation();
        }
        Location centre = region.center(world);
        centre.setY(region.maxY() + plugin.config().arena.spectatorHeightOffset);
        return centre;
    }

    public void onQuit(Player player) {
        SpleefSession current = session;
        if (current == null || !current.isParticipant(player.getUniqueId())) {
            return;
        }
        if (current.state() == GameState.RUNNING) {
            // Quitting eliminates you, so the round always stays decidable.
            Participant participant = current.participant(player.getUniqueId());
            if (participant != null && !participant.eliminated()) {
                participant.status(ParticipantStatus.ELIMINATED);
                plugin.messages().send(current.audience(), "game.quit-eliminated",
                        Placeholder.unparsed("player", player.getName()),
                        Placeholder.unparsed("remaining", String.valueOf(current.alive().size())));
            }
            plugin.scheduler().runLater(this::evaluate, 1);
        }
        plugin.freeze().thaw(player);
    }

    public void onJoin(Player player) {
        SpleefSession current = session;
        if (current != null && current.isParticipant(player.getUniqueId())) {
            plugin.hud().showTo(player);
            Participant participant = current.participant(player.getUniqueId());
            if (participant != null && participant.eliminated()) {
                player.setGameMode(GameMode.SPECTATOR);
            } else if (current.state() == GameState.ARMED) {
                plugin.freeze().freeze(player);
                plugin.hud().frozen(player);
            }
            return;
        }
        // Not in a session: if we still owe them items, this is the moment.
        // Covers disconnects, crashes and a shutdown mid-event alike.
        if (plugin.vault().hasPending(player.getUniqueId())) {
            plugin.loadout().strip(player);
            plugin.vault().restore(player, plugin.config().restoreMode);
            plugin.messages().send(player, "game.items-returned");
        }
    }

    // ------------------------------------------------------------------
    // resolution
    // ------------------------------------------------------------------

    /** Evaluates the win condition. Fires at most once per round. */
    public void evaluate() {
        SpleefSession current = session;
        if (current == null || current.state() != GameState.RUNNING) {
            return;
        }
        List<Participant> alive = current.alive();
        if (alive.size() > 1) {
            return;
        }
        if (!current.markResolved()) {
            return;
        }
        current.cancelTimer();
        current.state(GameState.ROUND_OVER);

        if (alive.size() == 1) {
            Participant winner = alive.get(0);
            plugin.hud().winner(everyoneAudience(), winner.name());
            plugin.messages().send(everyoneAudience(), "game.winner",
                    Placeholder.unparsed("player", winner.name()));
            plugin.hud().resultBar(plugin.messages().msg("bossbar.winner",
                    Placeholder.unparsed("player", winner.name())));
        } else {
            plugin.hud().draw(everyoneAudience());
            plugin.messages().send(everyoneAudience(), "game.draw");
            plugin.hud().resultBar(plugin.messages().msg("bossbar.draw"));
        }
        scheduleResultFade();
    }

    /**
     * The round timer ran out with players still standing.
     *
     * <p>They are eliminated directly rather than by {@code setHealth(0)}:
     * killing N players in one tick fires N death/respawn/evaluate cycles at
     * once, which is a genuine race. This reads the same on screen and resolves
     * cleanly to a draw.</p>
     */
    private void timeUp(SpleefSession current) {
        current.cancelTimer();
        if (!current.markResolved()) {
            return;
        }
        current.state(GameState.ROUND_OVER);

        for (Participant participant : current.participants()) {
            if (participant.eliminated()) {
                continue;
            }
            participant.status(ParticipantStatus.ELIMINATED);
            Player player = participant.online();
            if (player == null) {
                continue;
            }
            plugin.loadout().strip(player);
            player.setGameMode(GameMode.SPECTATOR);
            player.teleportAsync(spectatorSpot(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        }

        plugin.hud().draw(everyoneAudience());
        plugin.messages().send(everyoneAudience(), "game.time-up");
        plugin.hud().resultBar(plugin.messages().msg("bossbar.draw"));
        scheduleResultFade();
    }

    private void scheduleResultFade() {
        int seconds = Math.max(1, plugin.config().bossbar.resultDisplaySeconds);
        // Capture the bar being faded. Resolving it at fire time would hide
        // whatever bar is current then — i.e. the next round's, if the operator
        // moved on before the fade elapsed.
        BossBar fading = plugin.hud().bar();
        if (fading == null) {
            return;
        }
        plugin.scheduler().runLater(() -> {
            everyoneAudience().hideBossBar(fading);
            if (plugin.hud().bar() == fading) {
                plugin.hud().destroyBar();
            }
        }, seconds * 20L);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private @Nullable Arena resolveArena(Audience operator, @Nullable String name) {
        String target = name != null ? name : plugin.config().activeArena;
        if (target == null || target.isBlank()) {
            plugin.messages().send(operator, "area.no-active");
            return null;
        }
        Arena arena = plugin.arenaStore().get(target).orElse(null);
        if (arena == null) {
            plugin.messages().send(operator, "area.unknown", Placeholder.unparsed("name", target));
            return null;
        }
        if (!plugin.arenaStore().hasSnapshot(arena.name())) {
            plugin.messages().send(operator, "area.no-snapshot",
                    Placeholder.unparsed("name", arena.name()));
            return null;
        }
        plugin.arenaStore().refreshWorldId(arena);
        return plugin.arenaStore().get(arena.name()).orElse(arena);
    }

    /** @return null when the command should stop; a message has already been sent */
    private @Nullable List<Player> resolveParticipants(Audience operator) {
        SpleefConfig config = plugin.config();
        Roster roster = plugin.roster();

        if (roster.isEmpty()) {
            if (config.emptyRoster == SpleefConfig.EmptyRoster.REJECT) {
                plugin.messages().send(operator, "roster.empty-reject");
                return null;
            }
            roster.addAllOnline();
        }

        List<Player> players = new ArrayList<>();
        for (UUID id : roster.members()) {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                players.add(player);
            }
        }
        if (players.isEmpty()) {
            plugin.messages().send(operator, "roster.nobody-online");
            return null;
        }
        return players;
    }

    private void reject(Audience operator) {
        plugin.messages().send(operator, "game.bad-state",
                Placeholder.unparsed("state", state().name().toLowerCase(java.util.Locale.ROOT)));
    }

    private void showBarToAudience(SpleefConfig config) {
        if (config.bossbar.audience == SpleefConfig.BossBarAudience.EVERYONE) {
            plugin.hud().showTo(everyoneAudience());
        } else if (session != null) {
            plugin.hud().showTo(session.audience());
        }
    }

    private void hideBarFromEveryone() {
        plugin.hud().hideFrom(everyoneAudience());
    }

    private Audience everyoneAudience() {
        return Audience.audience(plugin.getServer().getOnlinePlayers());
    }

    private static double healthCap(Player player) {
        var attribute = player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        return attribute == null ? 20.0 : attribute.getValue();
    }
}
