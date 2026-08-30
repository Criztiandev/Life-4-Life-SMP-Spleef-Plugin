package dev.criztian.spleef.arena.snapshot;

import dev.criztian.spleef.arena.Cuboid;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Gzipped binary encoding for {@link BlockSnapshot}. */
public final class SnapshotCodec {

    private static final int MAGIC = 0x53504C46; // 'SPLF'
    private static final int VERSION = 1;

    private SnapshotCodec() {}

    /** Writes via a temp file so a crash mid-write cannot corrupt the live snapshot. */
    public static void write(Path target, BlockSnapshot snapshot) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");

        try (DataOutputStream out = new DataOutputStream(
                new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(temp))))) {
            Cuboid region = snapshot.region();
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(region.worldName());
            out.writeLong(region.worldId().getMostSignificantBits());
            out.writeLong(region.worldId().getLeastSignificantBits());
            out.writeInt(region.minX());
            out.writeInt(region.minY());
            out.writeInt(region.minZ());
            out.writeInt(region.maxX());
            out.writeInt(region.maxY());
            out.writeInt(region.maxZ());

            List<String> palette = snapshot.palette();
            out.writeInt(palette.size());
            for (String state : palette) {
                out.writeUTF(state);
            }

            int width = widthFor(palette.size());
            out.writeByte(width);
            int[] indices = snapshot.indices();
            for (int index : indices) {
                switch (width) {
                    case 1 -> out.writeByte(index);
                    case 2 -> out.writeShort(index);
                    default -> out.writeInt(index);
                }
            }
        }

        if (Files.exists(target)) {
            Files.move(target, target.resolveSibling(target.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static BlockSnapshot read(Path source) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new BufferedInputStream(Files.newInputStream(source))))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("Not a spleef snapshot: " + source);
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("Snapshot " + source.getFileName() + " is format v" + version
                        + ", this build reads v" + VERSION + " — re-save the arena");
            }
            String worldName = in.readUTF();
            UUID worldId = new UUID(in.readLong(), in.readLong());
            Cuboid region = new Cuboid(worldId, worldName,
                    in.readInt(), in.readInt(), in.readInt(),
                    in.readInt(), in.readInt(), in.readInt());

            int paletteSize = in.readInt();
            List<String> palette = new ArrayList<>(paletteSize);
            for (int i = 0; i < paletteSize; i++) {
                palette.add(in.readUTF());
            }

            int width = in.readByte();
            long volume = region.volume();
            if (volume > Integer.MAX_VALUE) {
                throw new IOException("Snapshot region is too large to load: " + volume + " blocks");
            }
            int[] indices = new int[(int) volume];
            for (int i = 0; i < indices.length; i++) {
                indices[i] = switch (width) {
                    case 1 -> in.readUnsignedByte();
                    case 2 -> in.readUnsignedShort();
                    default -> in.readInt();
                };
            }
            return new BlockSnapshot(region, List.copyOf(palette), indices);
        }
    }

    private static int widthFor(int paletteSize) {
        if (paletteSize <= 256) {
            return 1;
        }
        return paletteSize <= 65536 ? 2 : 4;
    }
}
