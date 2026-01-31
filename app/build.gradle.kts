import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("com.android.application")
    kotlin("android")
    id("kotlin-parcelize")
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("com.google.devtools.ksp")
    id("com.autonomousapps.dependency-analysis")
}

android {
    namespace = "vasyl.titles"
    compileSdk = 36

    defaultConfig {
        applicationId = "vasyl.titles"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["runnerBuilder"] = "de.mannodermaus.junit5.AndroidJUnit5Builder"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    flavorDimensions += "default"

    productFlavors {
        create("vasylTitles") {
            dimension = "default"
            applicationId = "vasyl.titles"
        }
        create("syuWidgetMusic") {
            dimension = "default"
            applicationId = "com.syu.widget.music"
        }
        create("syuScreensaver") {
            dimension = "default"
            applicationId = "com.syu.screensaver"
        }
        create("avaCar") {
            dimension = "default"
            applicationId = "com.ava.car"
        }
        create("teyesOnline") {
            dimension = "default"
            applicationId = "cn.teyes.online"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    signingConfigs {
        getByName("debug") {
            keyAlias = "android"
            keyPassword = "android"
            storeFile = file("../app/keystore.jks")
            storePassword = "android"
        }
        create("release") {
            keyAlias = "android"
            keyPassword = "android"
            storeFile = file("../app/keystore.jks")
            storePassword = "android"
        }
    }

    kotlinOptions {
        freeCompilerArgs += listOf(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:suppressKotlinVersionCompatibilityCheck=true"
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
      compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
      }
    }

    tasks.withType<Test> {
        useJUnit()
        useJUnitPlatform()
    }

    buildFeatures {
        compose = true
    }

    packaging {
        dex {
            useLegacyPackaging = false
        }
        resources.excludes.add("META-INF/**")
    }

    bundle {
        storeArchive {
            enable = false
        }
    }

    lint {
        //lintConfig = file("lint.xml")
        checkReleaseBuilds = false
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.flexbox)
    implementation(libs.storage)
    implementation("androidx.glance:glance:1.1.1")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")
    implementation("androidx.glance:glance-material:1.1.1")
    implementation(libs.androidx.work.runtime.ktx)
    implementation("androidx.window:window:1.2.0")
    implementation("androidx.palette:palette:1.0.0")

    ksp("androidx.room:room-compiler:2.8.4")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    debugImplementation(libs.leakcanary.android)
    debugImplementation(libs.leakcanary.android.core)
    debugImplementation(libs.leakcanary.object1.watcher.android.androidx)
    debugImplementation(libs.leakcanary.object1.watcher.android.support.fragments)
}