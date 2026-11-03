plugins { java }
group = "com.fizzysmp"
version = "1.0.0"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(files("libs/DiscordSRV-1.30.5.jar"))
    testImplementation(files("libs/DiscordSRV-1.30.5.jar"))
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8"; options.release.set(21) }
tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed") }
    // Match Paper's parent-first YAML classes rather than DiscordSRV's bundled copy.
    val runtime = sourceSets.test.get().runtimeClasspath
    classpath = files(runtime.filter { it.name != "DiscordSRV-1.30.5.jar" }, runtime.filter { it.name == "DiscordSRV-1.30.5.jar" })
}
tasks.jar {
    archiveBaseName.set("FizzyChat")
    manifest.attributes["paperweight-mappings-namespace"] = "mojang"
}

