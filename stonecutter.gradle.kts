plugins {
    id("dev.kikugie.stonecutter")
}
stonecutter active "1.21.4-fabric"

allprojects {
    repositories {
        mavenCentral()
        mavenLocal()
        maven("https://maven.fabricmc.net/")
        maven("https://api.modrinth.com/maven")
        maven("https://maven.terraformersmc.com/releases/")
        // maven.neoforged.net is added back when the NeoForge/Forge nodes are enabled: a
        // socket RESET (not a 404) from it aborts resolution of fabricmc's remapped groups
    }
}
