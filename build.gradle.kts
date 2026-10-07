plugins {
    id("dev.isxander.modstitch.base") version "0.8.5"
}

val minecraft = property("deps.minecraft") as String

modstitch {
    minecraftVersion = minecraft

    metadata {
        modId = "trade-master"
        modName = "Trade Master"
        modVersion = "1.0.0"
        modGroup = "dev.trademaster"
        modAuthor = "mknbt"
        modLicense = "GPL-3.0"
        modDescription = "Fully configurable librarian enchantment-book rerolling pipeline."
    }

    // Fabric Loom (Fabric targets). Loader version is intentionally not per-version.
    loom {
        fabricLoaderVersion = property("deps.fabric_loader") as String
    }

    // ModDevGradle (NeoForge / Forge targets) is wired in when those nodes are enabled.
}

// Stonecutter loader constants, matched from the target name suffix (e.g. "26.2-fabric").
stonecutter {
    constants.match(name.split("-")[1], "fabric", "neoforge", "forge", "vanilla")
}

dependencies {
    modstitch.loom {
        modstitchModImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
    }
}
