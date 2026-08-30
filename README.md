# Criztian Plugins

Monorepo for Paper Minecraft plugins built on a shared framework (`framework-core`).

- **Target:** Paper 26.2 (latest Minecraft, year-based versioning) · Java 25 · Folia-compatible
- **Build:** Gradle 9.7 (wrapper included — use `.\gradlew.bat`), Kotlin DSL, version catalog in [gradle/libs.versions.toml](gradle/libs.versions.toml)
- **Distribution model:** each plugin shades + relocates the framework and its libraries into its own namespace, so plugin jars are self-contained and never clash with each other on a server.

## Modules

| Module | What it is |
|---|---|
| `build-logic` | Gradle convention plugins (`framework.base`, `framework.paper-plugin`) |
| `framework-core` | The shared framework: scheduler, commands, config, messages, GUI, storage, integrations |
| `spleef` | Spleef event utilities: arena snapshots, rounds, freeze, item vault |

(`example-plugin` was removed from the working tree; its sources remain in git
history at `git show HEAD:example-plugin/...` as the reference implementation.)

## The framework in one glance

| Area | Built on | Framework entry point |
|---|---|---|
| Plugin lifecycle | Paper API | `FrameworkPlugin` base class |
| Scheduling (Folia-safe) | Paper's Global/Region/Async/Entity schedulers | `scheduler()` |
| Commands | Incendo Cloud v2 (+ native Brigadier completions) | `commands().register(...)` |
| Config | SpongePowered Configurate (typed classes, comments, migrations) | `configs().config(MyConfig.class, "config.yml")` |
| Messages | MiniMessage (`messages.yml`, `<prefix>` tag) | `messages().send(player, "key", ...)` |
| GUI | InvUI | `gui().open(player, gui, title)` |
| Storage | HikariCP + SQLite (default) / MariaDB·MySQL | `initStorage(cfg.storage)` |
| Items | Paper DataComponent API | `ItemBuilder.of(Material...)` |
| Placeholders | PlaceholderAPI (soft) | `placeholders().register(...)` |
| Economy | VaultUnlocked (soft) | `economy()` |
| Metrics | bStats | override `bstatsId()` |

## Common commands

```powershell
npm run build      # build everything (shaded plugin jars in <plugin>\build\libs)
npm run dev        # boot a local Paper 26.2 dev server with the plugin installed
npm run audit      # check the built jars for unrelocated dependencies
npm run verify     # build, then audit
npm run clean      # delete build output
npm run rebuild    # clean, then build
```

These are thin wrappers — Gradle is still the build system, so
`.\gradlew.bat build` and any other Gradle task work exactly as before. There
are no npm dependencies to install; `npm run <task>` works on a fresh clone.
Pass extra Gradle flags after `--`, e.g. `npm run build -- --stacktrace`.

The dev server lives in `spleef\run\` (gitignored). EULA is auto-accepted for dev via JVM flag.

## Spleef

Event utilities for running a spleef tournament: select an arena, snapshot it,
then drive rounds from four commands. Soft-depends on EssentialsX (for `/home`)
and Multiverse-Core (load order only); works fine without either.

### Running an event

```
/spleef wand                     # golden axe: left-click corner 1, right-click corner 2
/spleef area save main           # snapshots every block in the selection
/spleef area scan main           # dry run: how many usable spawns, which deck

/spleef roster all               # enrol everyone online (or /spleef roster open + /spleef join)
/spleef prepare                  # vault items, scatter, freeze, hand out shovels, 30:00 bar
/spleef start                    # unfreeze, timer runs, deaths become spectators
/spleef round 2                  # restore the platform, revive everyone, re-freeze
/spleef start                    # ...and cycle
/spleef end                      # restore items, send everyone home
```

`/spleef status` shows state, round, alive count and how many inventories are
being held. `/spleef vault list` and `/spleef vault restore <player>` are the
manual escape hatches.

### Things worth knowing

- **Items are real.** `prepare` takes custody of every participant's inventory
  into SQLite, committing before it clears anything. If the server dies mid-event,
  items are returned automatically the next time each player logs in — a restart
  does not lose them. Default `shutdown-policy: HOLD` is what makes that true.
- **The freeze leaves no residue.** It is implemented purely by cancelling events,
  never by attribute modifiers or potion effects, because those persist in player
  NBT — a crash mid-freeze with one of those applied would leave players
  permanently stuck. Do not "optimise" it into an attribute modifier.
- **Block entities are not captured.** A snapshot stores block *states*, not
  tile-entity NBT, so a sign or chest that gets destroyed comes back blank.
  `area save` warns when the region contains any.
- **An empty roster refuses to start.** `empty-roster: REJECT` is the default so
  `/spleef prepare` can never silently vault every inventory on the server.
- Arena data lives in `plugins/Spleef/arenas/<name>.yml` plus a gzipped
  `<name>.snapshot`; the item vault lives in the plugin's SQLite database.

### Tests

```
npm run build          # compiles and runs the test suite
./gradlew :spleef:test # tests only
```

66 unit tests cover the logic that can be checked without a server: region
geometry and index math, the snapshot codec (including the palette-width
boundaries where an off-by-one corrupts every block), arena file parsing,
roster persistence, deck detection, and spawn placement.

What they deliberately do not cover is anything that needs a live player —
the item vault's Bukkit-facing path, freeze, death handling, and the
end-of-event teleport. Those are manual: see the checklist in the Spleef
section, and **test the vault with a throwaway account holding recognisable
items before running a real event**, including a hard process kill mid-session.

## New plugin checklist

1. Add `include("my-plugin")` in [settings.gradle.kts](settings.gradle.kts).
2. Create `my-plugin/build.gradle.kts`:
   ```kotlin
   plugins { id("framework.paper-plugin") }

   dependencies {
       implementation(project(":framework-core"))
       compileOnly(libs.paper.api)
   }

   bukkit {
       name = "MyPlugin"
       main = "dev.criztian.myplugin.MyPlugin"
   }
   ```
3. Create `src/main/java/dev/criztian/myplugin/MyPlugin.java` extending `FrameworkPlugin`, implement `enable()`.
4. Add `src/main/resources/messages.yml` (at minimum a `prefix` key).
5. Never hand-write `plugin.yml` — it is generated by the build.

## Version bumps

All versions live in `gradle/libs.versions.toml`. When a new Minecraft drop lands, bump **together**: `paper-api`, `invui` (it targets one MC version at a time), and `runServer` / `apiVersion` in `build-logic/src/main/kotlin/framework.paper-plugin.gradle.kts`.

## Troubleshooting

**Build suddenly produces empty jars / "cannot find file" / "file mode" errors:**
a stale Kotlin compile daemon (often spawned on a different JDK by an IDE
extension) can poison compilation while still reporting success. Fix:

```powershell
.\gradlew.bat --stop
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
  Where-Object { $_.CommandLine -match 'KotlinCompileDaemon' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Remove-Item -Recurse -Force build-logic\build
.\gradlew.bat build
```

**`runServer` fails to restart:** a crashed dev server can keep `run/` file-locked — kill stray `java.exe` processes.

## Rules encoded in the build (do not fight them)

- Adventure/MiniMessage and snakeyaml are **never shaded** — Paper bundles them.
- Configurate, geantyref, Cloud, InvUI, bStats, and the framework itself are **always relocated** per plugin.
- No `Bukkit.getScheduler()` anywhere — use `scheduler()` (Folia compatibility depends on it).
- Database drivers + HikariCP arrive via `plugin.yml` `libraries:` (runtime download), not shading.
# MINECRAFT-PLUGIN-FRAMEWORK
# Life-4-Life-SMP-Spleef-Plugin
