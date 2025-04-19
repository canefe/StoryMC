import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
	kotlin("jvm") version "2.1.20"
	id("com.github.johnrengelman.shadow") version "8.1.1"
	id("xyz.jpenilla.run-paper") version "2.3.1"
	id("com.gradleup.shadow") version "8.3.3"
	id("org.jlleitschuh.gradle.ktlint") version "12.2.0"
}

group = "com.canefe"
version = "1.0-SNAPSHOT"

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
	maven(url = "https://repo.codemc.org/repository/maven-public/")
	maven(url = "https://mvn.lumine.io/repository/maven-public/")
}

dependencies {
	compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
	compileOnly("net.citizensnpcs:citizens-main:2.0.38-SNAPSHOT")
	compileOnly("org.mcmonkey:sentinel:2.9.1-SNAPSHOT")
	compileOnly(files("lib/RealisticSeasons.jar"))
	implementation("net.kyori:adventure-api:4.17.0")
	implementation("dev.jorel:commandapi-bukkit-shade:10.0.0")
	compileOnly("com.github.decentsoftware-eu:decentholograms:2.8.12")
	compileOnly("com.github.toxicity188:BetterHealthBar3:3.5.4")
	compileOnly("LibsDisguises:LibsDisguises:10.0.44")
	compileOnly("me.clip:placeholderapi:2.11.6")
	compileOnly("dev.lone:api-itemsadder:4.0.9")
	compileOnly("io.lumine:Mythic-Dist:5.6.1")
	implementation("com.squareup.okhttp3:okhttp:4.12.0")
	implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

	testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
	testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
	testImplementation("org.mockito:mockito-core:5.3.1")
	testImplementation("org.mockito:mockito-junit-jupiter:5.3.1")
	testImplementation("com.github.seeseemelk:MockBukkit-v1.19:2.29.0")
	testImplementation("commons-lang:commons-lang:2.6")
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

tasks {
	runServer {
		minecraftVersion("1.21")
	}
}
