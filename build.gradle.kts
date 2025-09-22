import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.io.FileInputStream
import java.util.*


val serverPluginsDir: String? =
    System.getenv("SERVER_PLUGINS_DIR")

val prodServerPluginsDir: String? =
    System.getenv("PROD_SERVER_PLUGINS_DIR")

// Load properties from local.properties file
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

// SSH deployment configuration - prefer environment variables over local.properties
// This allows for secure, portable deployment configuration without hardcoded credentials
val remoteUser: String? = System.getenv("REMOTE_USER") ?: localProperties.getProperty("REMOTE_USER")
val remoteHost: String? = System.getenv("REMOTE_HOST") ?: localProperties.getProperty("REMOTE_HOST")
val remotePath: String? = System.getenv("REMOTE_PATH") ?: localProperties.getProperty("REMOTE_PATH")

// OpenRouter API key for testing
val openRouterAPIKey: String? =
    System.getenv("OPENROUTER_API_KEY") ?: localProperties.getProperty("OPENROUTER_API_KEY")

plugins {
    kotlin("jvm") version "2.1.20"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("xyz.jpenilla.run-paper") version "2.3.1"
    id("com.gradleup.shadow") version "8.3.3"
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0"
    id("jacoco")
}

group = "com.canefe"
version = "0.2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc-repo" }
    maven("https://oss.sonatype.org/content/groups/public/") { name = "sonatype" }
    maven("https://maven.citizensnpcs.co/repo") { name = "citizensRepo" }
    maven("https://nexus.phoenixdevt.fr/repository/maven-public/") { name = "phoenix" }
    maven("https://repo.byteflux.net/nexus/repository/public/")
    maven("https://libraries.minecraft.net")
    maven("https://jitpack.io/")
    maven("https://repo.alessiodp.com/releases/")
    maven("https://repo.codemc.io/repository/maven-public/")
    maven("https://repo.md-5.net/content/groups/public/")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://maven.devs.beer/")
    maven { url = uri("https://repo.codemc.io/repository/maven-releases/") }
    maven { url = uri("https://repo.codemc.io/repository/maven-snapshots/") }
    maven(url = "https://repo.codemc.org/repository/maven-public/")
    maven(url = "https://mvn.lumine.io/repository/maven-public/")
    flatDir {
        dirs(
            "libs",
            "lib",
        )
    }
}

dependencies {
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v1.21:4.15.0")

    testImplementation("dev.jorel:commandapi-bukkit-test-toolkit:10.0.0")

    // shade
    implementation("dev.jorel:commandapi-bukkit-shade:10.0.0")
    // PaperMC
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")

    // Minecraft Stuff

    // Maven plugin dependencies
    implementation("net.byteflux:libby-bukkit:1.1.5")

    compileOnly("net.citizensnpcs:citizens-main:2.0.39-SNAPSHOT")
    compileOnly("org.mcmonkey:sentinel:2.9.1-SNAPSHOT")
    compileOnly("net.tnemc:EconomyCore:0.1.3.5-Release-1")
    implementation("net.kyori:adventure-api:4.21.0")
    compileOnly("com.github.decentsoftware-eu:decentholograms:2.8.12")
    compileOnly("com.github.toxicity188:BetterHealthBar3:3.5.4")
    compileOnly("LibsDisguises:LibsDisguises:10.0.44")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("dev.lone:api-itemsadder:4.0.9")
    compileOnly("io.lumine:Mythic-Dist:5.6.1")
    compileOnly("com.github.LeonMangler:SuperVanish:6.2.18-3")
    compileOnly("com.github.retrooper:packetevents-spigot:2.8.0")
    implementation("com.github.stefvanschie.inventoryframework:IF:0.10.19")
    compileOnly("io.lumine:MythicLib-dist:1.6.2-SNAPSHOT")
    compileOnly("net.Indyuce:MMOCore-API:1.12.1-SNAPSHOT")

    // Local plugin dependencies
    compileOnly(
        fileTree("lib") {
            include("RealisticSeasons.jar")
            include("ReviveMe-API.jar")
        },
    )

    // HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // Testing
    // Use a single, up-to-date MockBukkit coordinate for 1.21 compatibility
    testImplementation("io.mockk:mockk:1.14.5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.mockito:mockito-junit-jupiter:5.3.1")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.20")
    testImplementation("org.mockito:mockito-inline:4.8.0")
    testImplementation(files("lib/RealisticSeasons.jar"))

    // Add Gson if used by the plugin
    implementation("com.google.code.gson:gson:2.10.1") // Or the latest version

    // Audio conversion libraries
    implementation("com.googlecode.soundlibs:jlayer:1.0.1.4")
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    implementation("com.googlecode.soundlibs:tritonus-share:0.3.7.4")

    // Add Mockito-Kotlin for tests
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

val targetJavaVersion = 21

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
}

kotlin {
    jvmToolchain(targetJavaVersion)
}

ktlint {
    android.set(false)
    outputColorName.set("RED")
    ignoreFailures.set(true)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.test {
    doFirst {
        val toolkit = classpath.filter { it.name.contains("commandapi-bukkit-test-toolkit") }
        val shade = classpath.filter { it.name.contains("commandapi-bukkit-shade") }
        val rest =
            classpath.filter {
                !it.name.contains("commandapi-bukkit-test-toolkit") &&
                    !it.name.contains("commandapi-bukkit-shade")
            }
        classpath = files(toolkit, rest, shade)
    }
    useJUnitPlatform()

    // Configure test logging
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }

    // Set JVM arguments for testing
    jvmArgs("-Xmx2G", "-XX:+UseG1GC")

    // Enable parallel test execution
    maxParallelForks =
        Runtime
            .getRuntime()
            .availableProcessors()
            .div(2)
            .coerceAtLeast(1)

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }

    // Inject API key into test environment
    environment("OPENROUTER_API_KEY", openRouterAPIKey ?: "")

    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test) // tests must run before report

    reports {
        xml.required.set(true) // useful for CI
        csv.required.set(false)
        html.required.set(true) // human-readable in browser
    }
}

// Fast check for pre-commit
tasks.register("preCommit") {
    group = "verification"
    description = "Runs ktlintFormat and compile only (no tests)."

    dependsOn("ktlintFormat", "compileKotlin")
}

tasks.withType<ShadowJar> {
    relocate("dev.jorel.commandapi", "com.canefe.story.commandapi")
    relocate("com.github.stefvanschie.inventoryframework", "com.canefe.story.story.inventoryframework")
    archiveClassifier.set("")
}

sourceSets {
    // make stubs for local jar files that we should not publish for CI
    val stubs by creating {
        java {
            srcDir("src/stubs/kotlin")
        }
    }

    main {
        java {
            setSrcDirs(listOf("src/main/java", "src/main/kotlin"))
        }

        compileClasspath += stubs.output
        runtimeClasspath += stubs.output
    }

    test {
        resources {
            srcDir("src/test/resources")
            exclude("plugin.yml")
        }
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.register<Copy>("copyToServer") {
    dependsOn("build")
    dependsOn("shadowJar")

    val shadowJar = tasks.named<Jar>("shadowJar").get()
    from(shadowJar.archiveFile)

    // resolve env var once, during configuration
    val dir = "/Users/canefe/test-server/plugins"

    into(dir)

    doLast {
        println("✅ Copied fat plugin JAR to: $dir")
    }
}

tasks.register<Exec>("deployToSSH") {
    dependsOn("build")
    dependsOn("shadowJar")

    group = "deployment"
    description = "Deploy the plugin JAR to remote server via SSH"

    val shadowJar = tasks.named<Jar>("shadowJar").get()
    val localFile =
        shadowJar.archiveFile
            .get()
            .asFile.absolutePath

    // Use externalized configuration with validation
    val user =
        remoteUser
            ?: throw GradleException("REMOTE_USER not set. Set it as environment variable or in local.properties")
    val host =
        remoteHost
            ?: throw GradleException("REMOTE_HOST not set. Set it as environment variable or in local.properties")
    val path =
        remotePath
            ?: throw GradleException("REMOTE_PATH not set. Set it as environment variable or in local.properties")

    commandLine("scp", localFile, "$user@$host:$path")

    doLast {
        println("✅ Deployed plugin JAR to $user@$host:$path")
    }
}

tasks {
    runServer {
        minecraftVersion("1.21")
    }
}
