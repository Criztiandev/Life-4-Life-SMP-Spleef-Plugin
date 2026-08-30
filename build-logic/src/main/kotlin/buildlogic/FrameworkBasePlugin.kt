package buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.toolchain.JavaLanguageVersion

/**
 * `framework.base`: Java conventions shared by every module — toolchain 25
 * (required by MC 26.1+), UTF-8, and `-parameters` (load-bearing:
 * cloud-annotations resolves command argument names via reflection parameter
 * names; removing it silently breaks commands).
 */
class FrameworkBasePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.plugins.apply("java-library")

        project.extensions.configure(JavaPluginExtension::class.java) {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        }

        project.tasks.withType(JavaCompile::class.java).configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.add("-parameters")
        }
    }
}
