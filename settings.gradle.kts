pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()

        // Modstitch
        maven("https://maven.isxander.dev/releases/")

        // Fabric Loom platform
        maven("https://maven.fabricmc.net/")

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
         * @param loaders   "fabric" (1.14+), "vanilla" (Forge/NeoForge were dropped)
         */
        fun mc(mcVersion: String, name: String = mcVersion, loaders: Iterable<String>) =
            loaders.forEach { version("$name-$it", mcVersion) }

        // Fabric only: Forge/NeoForge targets are intentionally not built.
        // NB: 1.14-1.16 are Java 8, which cannot compile records/switch-expressions; the
        // shared codebase is modern, so those versions are out of scope (1.18.2+ = Java 17+).
        mc("1.18.2", loaders = listOf("fabric"))
        mc("1.19.2", loaders = listOf("fabric"))
        mc("1.20.1", loaders = listOf("fabric"))
        mc("1.21.1", loaders = listOf("fabric"))
        mc("1.21.4", loaders = listOf("fabric"))
        mc("1.21.5", loaders = listOf("fabric"))
        mc("1.21.8", loaders = listOf("fabric"))
        mc("26.1", loaders = listOf("fabric"))
        mc("26.2", loaders = listOf("fabric"))
        mc("26.3", loaders = listOf("fabric"))

        // Default target for the IDE / plain `./gradlew build`.
        vcsVersion = "1.21.4-fabric"
    }
}

rootProject.name = "TradeMaster"
