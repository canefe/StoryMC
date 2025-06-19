import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

val serverPluginsDir: String? =
	System.getenv("SERVER_PLUGINS_DIR")

val prodServerPluginsDir: String? =
	System.getenv("PROD_SERVER_PLUGINS_DIR")

plugins {
	kotlin("jvm") version "2.1.20"
	id("com.github.johnrengelman.shadow") version "8.1.1"
	id("xyz.jpenilla.run-paper") version "2.3.1"
	id("com.gradleup.shadow") version "8.3.3"
	id("org.jlleitschuh.gradle.ktlint") version "12.2.0"
}

group = "com.canefe"
version = "0.1.0-SNAPSHOT"

repositories {
	mavenCentral()
	maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc-repo" }
	maven("https://oss.sonatype.org/content/groups/public/") { name = "sonatype" }
	maven("https://maven.citizensnpcs.co/repo") { name = "citizensRepo" }
	maven("https://repo.byteflux.net/nexus/repository/public/")
	maven("https://libraries.minecraft.net")
	maven("https://jitpack.io/")
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
	// PaperMC
	compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")

	// Minecraft Stuff

	// Maven plugin dependencies
	compileOnly("net.citizensnpcs:citizens-main:2.0.38-SNAPSHOT")
	compileOnly("org.mcmonkey:sentinel:2.9.1-SNAPSHOT")
	compileOnly("net.tnemc:EconomyCore:0.1.3.5-Release-1")
	implementation("net.kyori:adventure-api:4.21.0")
	implementation("dev.jorel:commandapi-bukkit-shade:10.0.0")
	compileOnly("com.github.decentsoftware-eu:decentholograms:2.8.12")
	compileOnly("com.github.toxicity188:BetterHealthBar3:3.5.4")
	compileOnly("LibsDisguises:LibsDisguises:10.0.44")
	compileOnly("me.clip:placeholderapi:2.11.6")
	compileOnly("dev.lone:api-itemsadder:4.0.9")
	compileOnly("io.lumine:Mythic-Dist:5.6.1")
	compileOnly("com.github.LeonMangler:SuperVanish:6.2.18-3")
	compileOnly("com.github.retrooper:packetevents-spigot:2.8.0")
	implementation("com.github.stefvanschie.inventoryframework:IF:0.10.19")

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
	testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
	testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
	testImplementation("org.mockito:mockito-core:5.3.1")
	testImplementation("org.mockito:mockito-junit-jupiter:5.3.1")
	testImplementation("com.github.seeseemelk:MockBukkit-v1.19:2.29.0")
	testImplementation("commons-lang:commons-lang:2.6")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.20")
	testImplementation("org.mockito:mockito-inline:4.8.0")
	// Add Gson if used by the plugin
	implementation("com.google.code.gson:gson:2.10.1") // Or the latest version

	// Add Mockito-Kotlin for tests
	testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0") // Or the latest version compatible with your mockito-core

	// Update MockBukkit
	// Replace testImplementation("com.github.seeseemelk:MockBukkit-v1.19:2.29.0") with:
	testImplementation("com.github.MockBukkit:MockBukkit:v1.21-SNAPSHOT") // Find the latest version for 1.21
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
	useJUnitPlatform()
}

tasks.withType<ShadowJar> {
	relocate("dev.jorel.commandapi", "com.canefe.story.commandapi")
	relocate("com.github.stefvanschie.inventoryframework", "com.canefe.story.story.inventoryframework")
	archiveClassifier.set("")
}

sourceSets {
	main {
		java {
			setSrcDirs(listOf("src/main/java", "src/main/kotlin"))
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

	doFirst {
		requireNotNull(serverPluginsDir) {
			"❌ SERVER_PLUGINS_DIR is not set. Set it as an environment variable."
		}
	}

	val shadowJar = tasks.named<Jar>("shadowJar").get()
	from(shadowJar.archiveFile.get().asFile)
	into(serverPluginsDir!!)

	doLast {
		println("✅ Copied fat plugin JAR to: $serverPluginsDir")
	}
}
tasks.register<Copy>("copyToProdServer") {
	dependsOn("build")
	dependsOn("shadowJar")

	doFirst {
		requireNotNull(prodServerPluginsDir) {
			"❌ PROD_SERVER_PLUGINS_DIR is not set. Set it as an environment variable."
		}
	}

	val shadowJar = tasks.named<Jar>("shadowJar").get()
	from(shadowJar.archiveFile.get().asFile)
	into(prodServerPluginsDir!!)

	doLast {
		println("✅ Copied fat plugin JAR to: $prodServerPluginsDir")
	}
}

tasks {
	runServer {
		minecraftVersion("1.21")
	}
}
