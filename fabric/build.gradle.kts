plugins {
    id("net.fabricmc.fabric-loom") version "1.18-SNAPSHOT"
}

val fabricApiFor = mapOf(
    "26.1.2" to "0.155.3+26.1.2",
    "26.2" to "0.161.0+26.2",
    "26.3" to "0.162.0+26.3",
)
val minecraftVersion = providers.gradleProperty("mc").getOrElse("26.3")
val fabricApi = fabricApiFor[minecraftVersion]
    ?: error("No Fabric API version set for Minecraft $minecraftVersion, add it to fabricApiFor")

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:0.19.5")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApi")
    implementation(project(":core"))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    inputs.property("minecraft", minecraftVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to version, "minecraft" to minecraftVersion.split(".").take(2).joinToString(".") + ".x")
    }
}

tasks.jar {
    archiveBaseName.set("minevisor-live-fabric-mc" + minecraftVersion.split(".").take(2).joinToString("."))
    dependsOn(":core:jar")
    from(project(":core").sourceSets["main"].output)
}
