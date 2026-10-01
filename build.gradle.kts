// Top-level build file.

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 9 compiles Kotlin itself ("built-in Kotlin") and depends on the Kotlin Gradle plugin at
        // runtime. Putting KGP on the classpath here upgrades that dependency to the catalog version.
        // The org.jetbrains.kotlin.android plugin must NOT be applied anymore.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.dependency.analysis)
}
