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

        // Loaders per era: fabric everywhere; forge for <=1.20.1; neoforge for >=1.20.2.
        mc("1.16.5", loaders = listOf("fabric", "forge"))
        mc("1.18.2", loaders = listOf("fabric", "forge"))
        mc("1.19.2", loaders = listOf("fabric", "forge"))
        mc("1.20.1", loaders = listOf("fabric", "forge"))
        mc("1.21.1", loaders = listOf("fabric", "neoforge"))
        mc("1.21.4", loaders = listOf("fabric", "neoforge"))
        mc("1.21.5", loaders = listOf("fabric", "neoforge"))
        mc("1.21.8", loaders = listOf("fabric", "neoforge"))
        mc("26.1", loaders = listOf("fabric", "neoforge"))
        mc("26.2", loaders = listOf("fabric", "neoforge"))
        mc("26.3", loaders = listOf("fabric", "neoforge"))

        // Default target for the IDE / plain `./gradlew build`.
        vcsVersion = "1.21.4-fabric"
    }
}

rootProject.name = "TradeMaster"
