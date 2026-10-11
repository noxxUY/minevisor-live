
subprojects {
    apply(plugin = "java-library")

    repositories {
        mavenCentral()
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://oss.sonatype.org/content/repositories/snapshots/")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}

listOf(":core", ":bukkit").forEach { path ->
    project(path) {
        tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
    }
}
