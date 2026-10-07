plugins {
    id("dev.isxander.modstitch.base") version "0.8.5"
}

val minecraft = property("deps.minecraft") as String

fun prop(name: String, consumer: (String) -> Unit) {
    (findProperty(name) as? String?)?.let(consumer)
}

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

    // Fabric Loom (Fabric targets).
    loom {
        fabricLoaderVersion = property("deps.fabric_loader") as String
    }

    // ModDevGradle (NeoForge >=1.20.2, Forge <=1.20.1). Each target sets only its own
    // version property, so the other stays unset and unused for that target.
    moddevgradle {
        prop("deps.neoforge") { neoForgeVersion = it }
        prop("deps.forge") { forgeVersion = it }
        defaultRuns()
    }
}

// Stonecutter loader constants, matched from the target name suffix (e.g. "26.2-fabric").
stonecutter {
    constants.match(name.split("-")[1], "fabric", "neoforge", "forge", "vanilla")

    // Global rename: Mojang renamed ResourceLocation -> Identifier at 26.1 (the
    // unobfuscation release). Source is written with ResourceLocation; rewrite it for
    // 26.1+ so a single codebase compiles on 1.16.5..26.3.
    replacements.string(current.parsed >= "26.1") {
        replace("ResourceLocation", "Identifier")
    }
}

dependencies {
    modstitch.loom {
        modstitchModImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
    }

    // Shared, version-gated tests run on EVERY target's `test` task: this is how a
    // per-version API/behaviour regression is caught at build time.
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
