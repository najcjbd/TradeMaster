plugins {
    id("dev.isxander.modstitch.base") version "0.8.5"
}

val minecraft = property("deps.minecraft") as String

fun prop(name: String, consumer: (String) -> Unit) {
    (findProperty(name) as? String?)?.let(consumer)
}

modstitch {
    minecraftVersion = minecraft

    // Access widener. Authored once in the "named" (Mojmap) namespace; Modstitch
    // converts it to the per-loader/version format and namespace (intermediary for the
    // remapped <=1.21.11 Fabric targets, AT for Forge/NeoForge) and appends the
    // "accessWidener" entry to fabric.mod.json.
    classTweaker.set(rootProject.layout.projectDirectory.file("src/main/accesswidener/trade-master.accesswidener"))
    classTweakerName.set("trade-master.accesswidener")

    // Mixin config (declared in fabric.mod.json by Modstitch) + its refmap per target.
    mixin {
        addMixinsToModManifest.set(true)
        configs.register("trade-master") {
            config.set("trade-master.mixins.json")
            side.set(CLIENT)
        }
        registerSourceSet(sourceSets.getByName("main"), "trade-master.refmap.json")
    }

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

        // 26.1 also moved the Villager* classes into the npc.villager subpackage. The
        // source is written with the earlier package; longest class names first, since
        // "…npc.Villager" is a prefix of "…npc.VillagerData" and "…npc.VillagerType".
        replace("net.minecraft.world.entity.npc.VillagerProfession", "net.minecraft.world.entity.npc.villager.VillagerProfession")
        replace("net.minecraft.world.entity.npc.VillagerDataHolder", "net.minecraft.world.entity.npc.villager.VillagerDataHolder")
        replace("net.minecraft.world.entity.npc.VillagerData", "net.minecraft.world.entity.npc.villager.VillagerData")
        replace("net.minecraft.world.entity.npc.VillagerType", "net.minecraft.world.entity.npc.villager.VillagerType")
        replace("net.minecraft.world.entity.npc.AbstractVillager", "net.minecraft.world.entity.npc.villager.AbstractVillager")
        replace("net.minecraft.world.entity.npc.Villager", "net.minecraft.world.entity.npc.villager.Villager")

        // 26.1 reworked the client render surface. The source is written with the earlier
        // type name, text method and render method names; all are mapped for 26.1+. The one
        // exception is AbstractSelectionList$Entry, whose 26.1 method (extractContent) also
        // changed SIGNATURE, so it is gated per file instead of by replacement here.
        replace("GuiGraphics", "GuiGraphicsExtractor")
        replace("drawString(", "text(")
        replace("renderWidget(", "extractWidgetRenderState(")
        replace("renderListBackground(", "extractListBackground(")
        replace("renderListSeparators(", "extractListSeparators(")
        replace("renderPanorama(", "extractPanorama(")
        replace("renderMenuBackground(", "extractMenuBackground(")
        replace("renderBackground(", "extractBackground(")
        replace("render(", "extractRenderState(")
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
