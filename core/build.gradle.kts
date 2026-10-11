sourceSets["main"].resources.srcDir(rootProject.file("web-bundle"))

dependencies {
    compileOnly("com.google.code.gson:gson:2.11.0")
}
