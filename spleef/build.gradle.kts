plugins {
    id("framework.paper-plugin")
}

dependencies {
    implementation(project(":framework-core"))
    compileOnly(libs.paper.api)
    // Optional at runtime — resolved reflectively by integration/EssentialsHomes.
    compileOnly(libs.essentialsx.api)

    // paper-api is compileOnly for the plugin, but tests need it at runtime too.
    testImplementation(libs.paper.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}

bukkit {
    name = "Spleef"
    main = "dev.criztian.spleef.SpleefPlugin"
    description = "Spleef event utilities: arena snapshots, rounds, freeze, item vault"

    // MUST append. A plain assignment would drop the convention plugin's
    // PlaceholderAPI/Vault entries and silently break framework integrations.
    softDepend = softDepend.orEmpty() + listOf("Essentials", "Multiverse-Core")

    permissions {
        register("spleef.admin") {
            description = "Run the spleef event: wand, area, prepare, start, round, end"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.OP
        }
        register("spleef.play") {
            description = "Sign up for a spleef event with /spleef join"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.TRUE
        }
        register("spleef.bypass") {
            description = "Excluded from /spleef roster all"
            default = net.minecrell.pluginyml.bukkit.BukkitPluginDescription.Permission.Default.OP
        }
    }
}
