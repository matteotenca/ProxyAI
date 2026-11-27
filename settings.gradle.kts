plugins {
    // lets Gradle auto-download the JDK toolchain (25 for 2026.3)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ProxyAI"
include(":proxyai-treesitter")
include(":proxyai-telemetry")
