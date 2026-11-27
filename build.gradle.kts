import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask
import java.io.FileInputStream
import java.util.*

val localPropertiesFile = file("local.properties")
val env = environment("env").getOrNull()

fun loadProperties(filename: String): Properties = Properties().apply {
    load(FileInputStream(filename))
}

val localProperties: Properties? = if (localPropertiesFile.exists()) {
    loadProperties("local.properties")
} else {
    null
}

val customIdePath: String? = localProperties?.getProperty("customIdePath")

fun properties(key: String): Provider<String> {
    if ("win-arm64" == env) {
        val property = loadProperties("gradle-win-arm64.properties").getProperty(key)
            ?: return providers.gradleProperty(key)
        return providers.provider { property }
    }
    return providers.gradleProperty(key)
}

fun environment(key: String) = providers.environmentVariable(key)

val ideType: String = environment("IDE_TYPE").getOrElse("idea")

// plugin.xml hard-depends only on platform modules (Java/Kotlin/JS are optional), so the
// IDEA-built jar installs as-is in these IDEs; they get verification and run tasks, not separate builds
val otherIdes = mapOf(
    "RustRover" to IntelliJPlatformType.RustRover,
    "CLion" to IntelliJPlatformType.CLion,
    "PhpStorm" to IntelliJPlatformType.PhpStorm,
    "WebStorm" to IntelliJPlatformType.WebStorm,
    "PyCharm" to IntelliJPlatformType.PyCharm,
)

fun asProperty(key: String): Provider<String> {
    if (ideType == "android") {
        val asFile = File(projectDir, "gradle.properties.as")
        if (asFile.exists()) {
            val prop = loadProperties(asFile.absolutePath).getProperty(key)
                ?: providers.gradleProperty(key).get()
            return providers.provider { prop }
        }
    }
    return properties(key)
}

plugins {
    id("proxyai.java-conventions")
    id("org.jetbrains.intellij.platform")
    alias(libs.plugins.changelog)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.kotlin.serialization)
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get() + "-" + properties("pluginSinceBuild").get()

repositories {
    intellijPlatform {
        defaultRepositories()
    }
    mavenCentral()
    gradlePluginPortal()
}

changelog {
    groups.empty()
    repositoryUrl.set(properties("pluginRepositoryUrl"))
}

dependencies {
    intellijPlatform {
        if (ideType == "android") {
            androidStudio(asProperty("platformVersion"))
        } else {
            intellijIdea(properties("platformVersion"))
        }

        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("Git4Idea")
        // 2026.3 moved these out of the core platform classpath
        bundledPlugin("com.intellij.platform.vcs")
        bundledPlugin("com.intellij.modules.jcef")
        bundledModule("intellij.platform.vcs.core")
        bundledModule("intellij.platform.debugger")
        bundledModule("intellij.platform.debugger.impl")
        bundledModule("intellij.platform.ide.updateChecker")

        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.JUnit5)

        // merge into the main jar; IPGP 2.20 otherwise ships them as undeclared content modules in lib/modules
        pluginComposedModule(implementation(project(":proxyai-telemetry")))
        pluginComposedModule(implementation(project(":proxyai-treesitter")))
    }

    implementation(platform(libs.slf4j.bom))
    implementation(platform(libs.jackson.bom))
    implementation(platform(libs.mcp.sdk.bom))

    implementation(libs.jackson.datatype.jdk8)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.stdlib)
    implementation(libs.acp.sdk) {
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }

    implementation(libs.flexmark.all) {
        // vulnerable transitive dependency
        exclude(group = "org.jsoup", module = "jsoup")
    }
    implementation(libs.koog.agents) {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
    }
    implementation(libs.koog.serialization.jackson)
    implementation(libs.jsoup)
    implementation(libs.commons.text)
    implementation(libs.jtokkit)

    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.netty.shaded)
    implementation(libs.protobuf.runtime)

    implementation(libs.mcp.sdk)

    testImplementation(libs.junit4)
    testImplementation(kotlin("test"))
}

tasks.register<Exec>("updateSubmodules") {
    workingDir(rootDir)
    commandLine("git", "submodule", "update", "--init", "--recursive")
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        name = asProperty("pluginName").get()
        version = properties("pluginVersion").get() + "-" + asProperty("pluginSinceBuild").get()

        description =
            providers.fileContents(layout.projectDirectory.file("DESCRIPTION.md")).asText.map {
                val start = "<!-- Plugin description -->"
                val end = "<!-- Plugin description end -->"

                with(it.lines()) {
                    if (!containsAll(listOf(start, end))) {
                        throw GradleException("Plugin description section not found in DESCRIPTION.md:\n$start ... $end")
                    }
                    subList(indexOf(start) + 1, indexOf(end)).joinToString("\n")
                        .let(::markdownToHTML)
                }
            }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        // changelog headers use the full "<pluginVersion>-<sinceBuild>" version
        changeNotes = version.map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = asProperty("pluginSinceBuild").get()
            untilBuild = properties("pluginUntilBuild").get()
        }
    }

    pluginVerification {
        ides {
            recommended()
            otherIdes.values.forEach { create(it, properties("platformVersion")) }
        }
    }

    signing {
        certificateChain = System.getenv("CERTIFICATE_CHAIN")
        privateKey = System.getenv("PRIVATE_KEY")
        password = System.getenv("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = System.getenv("PUBLISH_TOKEN")
        channels = listOf("stable")
    }
}

/**
- * Task to run a custom IntelliJ IDEA sandbox.
- *
- * This task launches a custom IntelliJ IDEA installation using the path specified in the
- * 'customIdePath' property from local.properties.
- *
- * IMPORTANT:
- * - On macOS, the path must include the 'Contents' directory (e.g., /Applications/IntelliJ IDEA.app/Contents).
- * - For Windows or Linux, specify the appropriate path to the IntelliJ IDEA installation.
- *
- * Usage:
- *   ./gradlew runCustomIde
- */
if (customIdePath != null) {
    tasks.register<RunIdeTask>("runCustomIde") {
        group = "intellij"
        description = "Start custom idea sandbox"
        sandboxDirectory = file(customIdePath)
        environment("ENVIRONMENT", "LOCAL")
    }
}

intellijPlatformTesting {
    runIde {
        otherIdes.forEach { (name, ideType) ->
            register("run$name") {
                type = ideType
                version = properties("platformVersion")
                task {
                    environment("ENVIRONMENT", "LOCAL")
                }
            }
        }
    }
}

tasks {
    wrapper {
        gradleVersion = properties("gradleVersion").get()
    }

    prepareSandbox {
        dependsOn("updateSubmodules")
        from(layout.projectDirectory.dir("src/main/cpp/llama.cpp")) {
            into("ProxyAI/llama.cpp")
        }
    }

    runIde {
        environment("ENVIRONMENT", "LOCAL")
    }

    buildPlugin {
        dependsOn("prepareSandbox")
    }

    publishPlugin {
        dependsOn("patchChangelog")
    }

    test {
        exclude("**/testsupport/*")
        testLogging {
            events("started", "passed", "skipped", "failed")
            exceptionFormat = TestExceptionFormat.FULL
            showStandardStreams = true
        }
    }
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${libs.versions.grpc.get()}"
        }
    }
    generateProtoTasks {
        all()
            .forEach {
                it.plugins {
                    create("grpc")
                }
            }
    }
}
