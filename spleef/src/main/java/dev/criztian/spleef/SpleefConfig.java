package dev.criztian.spleef;

import dev.criztian.framework.storage.StorageSettings;
import java.util.List;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

@ConfigSerializable
public final class SpleefConfig {

    @Comment("Arena used by /spleef prepare when no name is given. Set with /spleef area use <name>.")
    public String activeArena = "";

    @Comment("Round length in seconds. 1800 = 30 minutes.\n"
            + "Plain seconds rather than a duration string — Configurate 4.2 has no\n"
            + "guaranteed java.time.Duration serializer.")
    public int roundSeconds = 1800;

    @Comment("Refuse /spleef start below this many participants. Use /spleef start force to override.")
    public int minParticipants = 2;

    @Comment("Seconds a disconnected player stays alive before being eliminated.\n"
            + "0 = quitting eliminates you immediately, which keeps the win condition always decidable.")
    public int quitGraceSeconds = 0;

    @Comment("What /spleef prepare does when the roster is empty.\n"
            + "REJECT     - refuse and tell the operator to run /spleef roster all (default; safe)\n"
            + "ALL_ONLINE - silently enrol every online player without spleef.bypass\n"
            + "REJECT is the default deliberately: ALL_ONLINE on an accidentally empty\n"
            + "roster would vault the inventory of every player on the server.")
    public EmptyRoster emptyRoster = EmptyRoster.REJECT;

    @Comment("What happens to vaulted items when the server stops mid-event.\n"
            + "HOLD    - items stay vaulted and are returned when the player next joins (default)\n"
            + "RESTORE - try to hand items back during shutdown (teleports/writes may not flush)")
    public ShutdownPolicy shutdownPolicy = ShutdownPolicy.HOLD;

    @Comment("How a vaulted inventory is put back.\n"
            + "OVERWRITE           - discard whatever is held in-game and restore the vault (default)\n"
            + "MERGE_DROP_OVERFLOW - merge, dropping anything that does not fit")
    public RestoreMode restoreMode = RestoreMode.OVERWRITE;

    @Comment("Item given by /spleef wand.")
    public String wandMaterial = "GOLDEN_AXE";

    public Limits limits = new Limits();
    public Capture capture = new Capture();
    public Restore restore = new Restore();
    public Scatter scatter = new Scatter();
    public Arena arena = new Arena();
    public Shovel shovel = new Shovel();
    public Protections protections = new Protections();
    public BossBarSettings bossbar = new BossBarSettings();

    @Comment("Where the item vault and session journal are stored.")
    public StorageSettings storage = new StorageSettings();

    public enum EmptyRoster { REJECT, ALL_ONLINE }

    public enum ShutdownPolicy { HOLD, RESTORE }

    public enum RestoreMode { OVERWRITE, MERGE_DROP_OVERFLOW }

    public enum BossBarAudience { PARTICIPANTS, EVERYONE }

    @ConfigSerializable
    public static final class Limits {
        @Comment("Largest selectable region, in blocks. A snapshot holds 4 bytes per block\n"
                + "in memory, so 2,000,000 blocks is roughly 8 MB.")
        public long maxVolume = 2_000_000L;

        @Comment("Largest number of (x,z) columns the scatter scanner will walk.\n"
                + "Shares an error path with max-volume so the two caps cannot disagree.")
        public long maxScanColumns = 250_000L;
    }

    @ConfigSerializable
    public static final class Capture {
        @Comment("Chunks read per tick while snapshotting an arena.")
        public int chunksPerTick = 4;

        @Comment("Fail a capture or restore if no chunk makes progress for this long.\n"
                + "Guards against a region task that never runs.")
        public int watchdogSeconds = 30;
    }

    @ConfigSerializable
    public static final class Restore {
        @Comment("Blocks compared per tick while resetting an arena. Only blocks that\n"
                + "actually differ are written, so a typical round resets in well under a second.")
        public int blocksPerTick = 20_000;

        @Comment("Re-write changed blocks a second time with physics enabled.\n"
                + "Only needed if support-requiring blocks (torches, carpets) misbehave.")
        public boolean finalPhysicsPass = false;

        @Comment("Remove dropped items, projectiles, XP orbs and falling blocks inside\n"
                + "the arena before restoring.")
        public boolean sweepEntities = true;
    }

    @ConfigSerializable
    public static final class Scatter {
        @Comment("Preferred minimum distance between two players' spawn points, in blocks.\n"
                + "Relaxed automatically when the platform is too small for the player count.")
        public double minSpacing = 4.0;

        @Comment("How many blocks below the highest valid standing surface still counts\n"
                + "as the top platform. Keeps everyone on one deck of a layered arena.")
        public int platformTolerance = 2;
    }

    @ConfigSerializable
    public static final class Arena {
        @Comment("Blocks participants may dig, while the round is running and inside the arena.")
        public List<String> diggableMaterials = List.of("SNOW_BLOCK", "SNOW", "WHITE_WOOL");

        @Comment("Height above the top of the arena where eliminated players are placed.")
        public int spectatorHeightOffset = 12;
    }

    @ConfigSerializable
    public static final class Shovel {
        public String material = "GOLDEN_SHOVEL";
        public String name = "<gold><bold>Spleef Shovel</bold></gold>";
        public List<String> lore = List.of("<gray>Dig the floor out from under them.</gray>");

        @Comment("0 disables the enchantment.")
        public int efficiency = 5;
        public boolean unbreakable = true;
    }

    @ConfigSerializable
    public static final class Protections {
        @Comment("Cancel player-versus-player damage during a round.")
        public boolean noPvp = true;

        @Comment("Cancel fall damage inside the arena (players are eliminated by the void, not the drop).")
        public boolean noFallDamage = true;

        @Comment("Stop hunger draining during a round.")
        public boolean noHunger = true;
    }

    @ConfigSerializable
    public static final class BossBarSettings {
        @Comment("Who sees the round timer: PARTICIPANTS or EVERYONE.")
        public BossBarAudience audience = BossBarAudience.EVERYONE;

        @Comment("Bar colour above the warning threshold. One of PINK BLUE RED GREEN YELLOW PURPLE WHITE.")
        public String color = "GREEN";

        @Comment("Bar colour once this fraction of the round remains.")
        public String warnColor = "YELLOW";
        public double warnAt = 0.25;

        @Comment("Bar colour once this fraction of the round remains.")
        public String urgentColor = "RED";
        public double urgentAt = 0.10;

        @Comment("Seconds the winner/draw result stays on the bar before it disappears.")
        public int resultDisplaySeconds = 10;
    }
}
