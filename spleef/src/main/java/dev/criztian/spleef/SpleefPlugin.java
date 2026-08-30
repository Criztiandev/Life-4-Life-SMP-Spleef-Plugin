package dev.criztian.spleef;

import dev.criztian.framework.config.ConfigHandle;
import dev.criztian.framework.plugin.FrameworkPlugin;
import dev.criztian.framework.storage.StorageService;
import dev.criztian.spleef.arena.ArenaService;
import dev.criztian.spleef.arena.ArenaStore;
import dev.criztian.spleef.arena.wand.SelectionService;
import dev.criztian.spleef.arena.wand.WandItem;
import dev.criztian.spleef.arena.wand.WandListener;
import dev.criztian.spleef.command.ArenaCommands;
import dev.criztian.spleef.command.RosterCommands;
import dev.criztian.spleef.command.SpleefAdminCommands;
import dev.criztian.spleef.game.Roster;
import dev.criztian.spleef.game.SpleefManager;
import dev.criztian.spleef.integration.HomeLocator;
import dev.criztian.spleef.listener.ConnectionListener;
import dev.criztian.spleef.listener.GameListener;
import dev.criztian.spleef.player.FreezeService;
import dev.criztian.spleef.player.Hud;
import dev.criztian.spleef.player.ItemVault;
import dev.criztian.spleef.player.Loadout;
import org.bukkit.Material;

public final class SpleefPlugin extends FrameworkPlugin {

    private ConfigHandle<SpleefConfig> config;
    private ArenaStore arenaStore;
    private ArenaService arenas;
    private WandItem wand;
    private SelectionService selections;
    private ItemVault vault;
    private Roster roster;
    private FreezeService freeze;
    private Hud hud;
    private Loadout loadout;
    private HomeLocator homes;
    private SpleefManager games;
    private GameListener gameListener;

    @Override
    protected void enable() {
        config = configs().config(SpleefConfig.class, "config.yml");

        StorageService storage = initStorage(config.get().storage);
        storage.migrate(ItemVault.MIGRATION);
        vault = new ItemVault(storage, getSLF4JLogger());
        // Surfaces an event that died mid-flight. Vault rows are left pending on
        // purpose so every affected player is made whole on their next login.
        vault.recoverOrphanedSessions();

        arenaStore = new ArenaStore(getDataFolder().toPath(), getSLF4JLogger());
        arenaStore.loadAll();
        arenas = new ArenaService(this, scheduler(), arenaStore, this::config, getSLF4JLogger());

        roster = new Roster(getDataFolder().toPath(), getSLF4JLogger());
        roster.load();

        wand = new WandItem(this);
        selections = new SelectionService();
        freeze = new FreezeService();
        hud = new Hud(messages());
        loadout = new Loadout(this);
        homes = new HomeLocator(getSLF4JLogger());
        games = new SpleefManager(this);
        gameListener = new GameListener(this);

        getServer().getPluginManager().registerEvents(
                new WandListener(wand, selections, messages()), this);
        getServer().getPluginManager().registerEvents(freeze, this);
        getServer().getPluginManager().registerEvents(gameListener, this);
        getServer().getPluginManager().registerEvents(new ConnectionListener(this), this);

        commands().register(
                new SpleefAdminCommands(this),
                new ArenaCommands(this),
                new RosterCommands(this));

        getSLF4JLogger().info("Spleef ready — /spleef status");
    }

    /**
     * Runs before any framework service is torn down, while storage, scheduling
     * and the server are all still live — which is exactly what the session
     * teardown needs.
     */
    @Override
    protected void disable() {
        if (games != null) {
            games.shutdown();
        }
        if (freeze != null) {
            freeze.thawAll();
        }
        if (arenas != null) {
            arenas.cancelActive();
            arenas.unpinAll();
        }
    }

    // --- accessors ---

    public SpleefConfig config() {
        return config.get();
    }

    public ArenaService arenas() {
        return arenas;
    }

    public ArenaStore arenaStore() {
        return arenaStore;
    }

    public WandItem wand() {
        return wand;
    }

    public SelectionService selections() {
        return selections;
    }

    public ItemVault vault() {
        return vault;
    }

    public Roster roster() {
        return roster;
    }

    public FreezeService freeze() {
        return freeze;
    }

    public Hud hud() {
        return hud;
    }

    public Loadout loadout() {
        return loadout;
    }

    public HomeLocator homes() {
        return homes;
    }

    public SpleefManager games() {
        return games;
    }

    public Material wandMaterial() {
        Material material = Material.matchMaterial(config().wandMaterial);
        return material == null ? Material.GOLDEN_AXE : material;
    }

    /** Sets the arena {@code /spleef prepare} defaults to, and persists it. */
    public void activeArena(String name) {
        config.get().activeArena = name;
        config.save();
    }

    public void reloadAll() {
        config.reload();
        messages().reload();
        arenaStore.loadAll();
        gameListener.refreshDiggable();
    }
}
