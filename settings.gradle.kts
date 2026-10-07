pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()

        // Modstitch
        maven("https://maven.isxander.dev/releases/")

        // Fabric Loom platform
        maven("https://maven.fabricmc.net/")

        // MDG platform (NeoForge / Forge)
        maven("https://maven.neoforged.net/releases/")

        // Stonecutter
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
}

stonecutter {
    kotlinController = true
    centralScript = "build.gradle.kts"

    create(rootProject) {
        /**
         * @param mcVersion the base Minecraft version
         * @param loaders   "fabric" (1.14+), "neoforge" (1.20.2+), "forge" (<=1.20.1), "vanilla"
         */
        fun mc(mcVersion: String, name: String = mcVersion, loaders: Iterable<String>) =
            loaders.forEach { version("$name-$it", mcVersion) }

        // Start with Fabric first (validated on this machine). NeoForge/Forge nodes are
        // added as the loader buildscripts are ported (maven.neoforged.net is flaky here).
        mc("1.21.4", loaders = listOf("fabric"))
        mc("26.2", loaders = listOf("fabric"))

        // Default target for the IDE / plain `./gradlew build`.
        vcsVersion = "1.21.4-fabric"
    }
}

rootProject.name = "TradeMaster"
