package buildlogic

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.minecrell.pluginyml.bukkit.BukkitPluginDescription
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.bundling.Jar
import xyz.jpenilla.runpaper.task.RunServer

/**
 * `framework.paper-plugin`: everything a plugin module needs — shadow with the
 * standard relocations, run-paper dev server, and a generated plugin.yml.
 * Module build files only declare dependencies and `bukkit { name; main }`.
 */
class FrameworkPaperPlugin : Plugin<Project> {

    /** Bump alongside `paper`/`invui` in gradle/libs.versions.toml. */
    private companion object {
        const val MINECRAFT_VERSION = "26.2"
    }

    override fun apply(project: Project) {
        project.plugins.apply(FrameworkBasePlugin::class.java)
        project.plugins.apply("com.gradleup.shadow")
        project.plugins.apply("xyz.jpenilla.run-paper")
        project.plugins.apply("de.eldoria.plugin-yml.bukkit")

        val convention = project.extensions.create("paperPlugin", PaperPluginConvention::class.java)
        convention.relocationBase.convention(
            "${project.group}.${project.name.replace(Regex("[^A-Za-z0-9]"), "")}.libs"
        )

        // Heavy Maven-Central deps land in plugin.yml `libraries:` — Paper
        // downloads them at load time instead of us shading them. One catalog
        // version for the whole monorepo means every plugin requests the same
        // sqlite native binary.
        val libs = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        for (alias in listOf("hikari", "sqlite-jdbc", "mariadb-client")) {
            project.dependencies.addProvider("bukkitLibrary", libs.findLibrary(alias).orElseThrow {
                IllegalStateException("Missing catalog library: $alias")
            })
        }

        // Paper bundles the whole Adventure stack (net.kyori, incl. option) and
        // snakeyaml — shading them breaks type identity at runtime. Strip from
        // the runtime graph so shadow can never pack them, regardless of which
        // dependency drags them in. They stay on the compile classpath.
        project.configurations.named("runtimeClasspath") {
            exclude(mapOf("group" to "net.kyori"))
            exclude(mapOf("group" to "org.yaml"))
            // compile-time-only annotation jars — dead weight in a plugin jar
            exclude(mapOf("group" to "org.jetbrains", "module" to "annotations"))
            exclude(mapOf("group" to "org.jspecify"))
            exclude(mapOf("group" to "org.checkerframework"))
        }

        project.extensions.configure(BukkitPluginDescription::class.java) {
            apiVersion = MINECRAFT_VERSION
            foliaSupported = true
            softDepend = listOf("PlaceholderAPI", "Vault") // VaultUnlocked registers as "Vault"
            version = project.version.toString()
        }

        project.tasks.named("jar", Jar::class.java) {
            archiveClassifier.set("unshaded")
        }

        project.tasks.named("shadowJar", ShadowJar::class.java) {
            archiveClassifier.set("") // the shaded jar is THE plugin jar
            // Let the service-file transformer see all duplicates instead of
            // Gradle silently dropping them before merging.
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
            mergeServiceFiles()
            // No minimize(): it strips classes reached only via reflection
            // (cloud-annotations parsing, InvUI internals).
        }

        // relocate() isn't lazy — resolve the convention after build scripts ran.
        project.afterEvaluate {
            val base = convention.relocationBase.get()
            project.tasks.named("shadowJar", ShadowJar::class.java) {
                relocate("dev.criztian.framework", "$base.framework")
                relocate("org.incendo.cloud", "$base.cloud")
                relocate("org.spongepowered.configurate", "$base.configurate")
                relocate("io.leangen.geantyref", "$base.geantyref")
                relocate("xyz.xenondevs", "$base.xenondevs")
                relocate("org.bstats", "$base.bstats")
            }
        }

        project.tasks.named("build") {
            dependsOn(project.tasks.named("shadowJar"))
        }

        project.tasks.named("runServer", RunServer::class.java) {
            minecraftVersion(MINECRAFT_VERSION)
            // Unattended dev-server smoke tests; implies acceptance of the MC EULA.
            jvmArgs("-Dcom.mojang.eula.agree=true")
        }
    }
}
