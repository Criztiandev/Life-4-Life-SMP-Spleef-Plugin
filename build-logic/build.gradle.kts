plugins {
    `kotlin-dsl`
}

// Turns a catalog plugin entry into its plugin-marker artifact coordinates,
// so plugin versions live only in libs.versions.toml.
fun plugin(dep: Provider<PluginDependency>) =
    dep.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

dependencies {
    implementation(plugin(libs.plugins.shadow))
    implementation(plugin(libs.plugins.run.paper))
    implementation(plugin(libs.plugins.plugin.yml))
}

gradlePlugin {
    plugins {
        register("frameworkBase") {
            id = "framework.base"
            implementationClass = "buildlogic.FrameworkBasePlugin"
        }
        register("frameworkPaperPlugin") {
            id = "framework.paper-plugin"
            implementationClass = "buildlogic.FrameworkPaperPlugin"
        }
    }
}
