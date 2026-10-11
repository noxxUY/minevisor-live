dependencies {
    compileOnly("org.spigotmc:spigot-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.3")
    implementation(project(":core"))
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks.jar {
    archiveBaseName.set("minevisor-live-bukkit")
    dependsOn(":core:jar")
    from(project(":core").sourceSets["main"].output)
}
