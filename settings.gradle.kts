pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") // paper-api
        maven("https://repo.xenondevs.xyz/releases")              // InvUI
        maven("https://repo.extendedclip.com/releases/")          // PlaceholderAPI
        maven("https://repo.codemc.io/repository/creatorfromhell/") // VaultUnlockedAPI
        maven("https://repo.essentialsx.net/releases/")           // EssentialsX
    }
}

rootProject.name = "criztian-plugins"

include("framework-core")
include("spleef")
// example-plugin was removed from the working tree; its sources remain at
// `git show HEAD:example-plugin/...` as the reference implementation.
