plugins {
    alias(libs.plugins.android.application)
    // AGP 9 compiles Kotlin natively; org.jetbrains.kotlin.android must not be applied anymore.
    alias(libs.plugins.kotlin.compose)
    // Removed as unused: kotlin-parcelize (no @Parcelize class) and KSP (it only ran the Room
    // compiler, and the project has no @Database / @Dao / @Entity).
    alias(libs.plugins.dependency.analysis)
}

// Optional dedicated key for the "phone" flavor, see gradle.properties.
val phoneStoreFile: String? = providers.gradleProperty("phoneStoreFile").orNull

android {
    namespace = "vasyl.titles"
    compileSdk {
        version = release(37)
    }

    // Must be declared before it is referenced by defaultConfig / productFlavors.
    signingConfigs {
        // Platform key: the system flavors run with android:sharedUserId="android.uid.system", so they
        // must be signed with the same key as the firmware.
        getByName("debug") {
            storeFile = file("keystore.jks")
            storePassword = "android"
            keyAlias = "android"
            keyPassword = "android"
        }
        create("platform") {
            storeFile = file("keystore.jks")
            storePassword = "android"
            keyAlias = "android"
            keyPassword = "android"
        }
        if (phoneStoreFile != null) {
            create("phone") {
                storeFile = file(phoneStoreFile)
                storePassword = providers.gradleProperty("phoneStorePassword").orNull
                keyAlias = providers.gradleProperty("phoneKeyAlias").orNull
                keyPassword = providers.gradleProperty("phoneKeyPassword").orNull
            }
        }
    }

    defaultConfig {
        applicationId = "vasyl.titles"
        minSdk = 26
        targetSdk = 36
        
        val appVersionName = "1.1.3"
        versionName = appVersionName
        // 1.1.3 -> 10103; every release gets a higher versionCode automatically.
        // A higher versionCode in /oem/priv-app makes the system drop an older /data/app update.
        versionCode = appVersionName.split(".").map(String::toInt)
            .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Release builds were previously unsigned. The flavor decides which key is used; debug builds
        // always use the "debug" config above (which is the platform key as well).
        signingConfig = signingConfigs.getByName("platform")

        // true = flavor is meant to be installed as a system app (android.uid.system).
        buildConfigField("boolean", "SYSTEM_BUILD", "true")
    }

    flavorDimensions += "default"

    productFlavors {
        create("vasylTitles") {
            dimension = "default"
            applicationId = "vasyl.titles"
            // Together with the debug build type: vasylTitlesDebug is selected after a Gradle sync.
            isDefault = true
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
        // Regular (non-system) app for phones and tablets. src/phone/AndroidManifest.xml removes
        // android:sharedUserId and the permissions that only the system UID can hold.
        create("phone") {
            dimension = "default"
            applicationId = "vasyl.titles.phone"
            versionNameSuffix = "-phone"
            buildConfigField("boolean", "SYSTEM_BUILD", "false")
            signingConfig = signingConfigs.findByName("phone") ?: signingConfigs.getByName("platform")
        }
    }

    buildTypes {
        debug {
            isDefault = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // No signingConfig here on purpose: a build type config would override the flavor one.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        dex {
            // Store dex uncompressed and page aligned (better for apps installed in /system).
            useLegacyPackaging = false
        }
        resources {
            // Do NOT exclude META-INF/** - META-INF/services is needed at runtime (e.g. by coroutines).
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "DebugProbesKt.bin",
            )
        }
    }

    bundle {
        storeArchive {
            enable = false
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    testOptions {
        // Unit tests run against a stub android.jar; the parser logs through android.util.Log.
        unitTests.isReturnDefaultValues = true
    }
}

// With built-in Kotlin the JVM target follows compileOptions.targetCompatibility (21), so no
// kotlin { compilerOptions { jvmTarget } } block is needed. The old
// suppressKotlinVersionCompatibilityCheck flag is obsolete with the Compose compiler Gradle plugin.

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.material)
    // Used by the color picker layout (no code reference, so do not let an IDE "remove unused" it).
    implementation(libs.flexbox)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.datastore.preferences)
    // Not used directly: pins the WorkManager version that Glance uses internally.
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.window)
    implementation(libs.androidx.palette)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    debugImplementation(libs.leakcanary.android)
}

tasks.withType<Test>().configureEach {
    // Plain JUnit 4 (useJUnitPlatform() without a JUnit 5 engine silently ran no tests).
    useJUnit()
}
