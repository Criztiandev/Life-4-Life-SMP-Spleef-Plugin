package dev.criztian.spleef;

import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.World;

/**
 * A {@link World} stub built with a dynamic proxy.
 *
 * <p>Only the handful of methods the region code actually calls are answered;
 * everything else throws, which keeps a test honest about what it depends on.
 * A proxy avoids pulling in a mocking framework for four getters.</p>
 */
public final class TestWorlds {

    private TestWorlds() {}

    public static World world(String name, int minHeight, int maxHeight) {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes());
        return (World) Proxy.newProxyInstance(
                TestWorlds.class.getClassLoader(),
                new Class<?>[] {World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> id;
                    case "getName" -> name;
                    case "getMinHeight" -> minHeight;
                    case "getMaxHeight" -> maxHeight;
                    // Identity semantics: Cuboid.of compares the two corners' worlds.
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "TestWorld[" + name + "]";
                    default -> throw new UnsupportedOperationException(
                            "TestWorlds stub does not implement " + method.getName());
                });
    }

    /** A vanilla-shaped overworld: buildable from -64 to 319. */
    public static World overworld() {
        return world("world", -64, 320);
    }
}
