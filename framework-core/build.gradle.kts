plugins {
    id("framework.base")
}

dependencies {
    // Provided by the server (includes Adventure/MiniMessage, Brigadier, Folia schedulers)
    compileOnly(libs.paper.api)
    // Provided by their plugins at runtime (soft-depends)
    compileOnly(libs.placeholderapi)
    compileOnly(libs.vaultunlocked.api)
    // Arrive via consuming plugin's generated plugin.yml `libraries:`
    compileOnly(libs.hikari)
    compileOnly(libs.sqlite.jdbc)
    compileOnly(libs.mariadb.client)

    // Shaded + relocated into each consuming plugin. `api` so plugin modules
    // can use these types directly through project(":framework-core").
    api(libs.cloud.paper)
    api(libs.cloud.brigadier)
    api(libs.cloud.annotations)
    api(libs.cloud.minecraft.extras) {
        exclude(group = "net.kyori") // Adventure is bundled in Paper — never shade
    }
    api(libs.configurate.yaml) {
        exclude(group = "org.yaml") // snakeyaml is bundled in Paper
    }
    api(libs.invui)
    implementation(libs.bstats.bukkit)
}
