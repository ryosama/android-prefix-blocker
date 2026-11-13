import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingProperties = Properties().apply {
    val source = rootProject.file(".private/signing.properties")
    if (source.exists()) source.inputStream().use { load(it) }
}

android {
    namespace = "com.prefixblocker.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.prefixblocker.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (signingProperties.isNotEmpty()) {
            create("localRelease") {
                storeFile = rootProject.file(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingProperties.isNotEmpty()) signingConfig = signingConfigs.getByName("localRelease")
        }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.maxHeapSize = "1536m"
            val screenshotDirectory = providers.gradleProperty("screenshotsDir").orNull
            if (screenshotDirectory != null) test.systemProperty("screenshots.dir", screenshotDirectory)
        }
    }
    lint { warningsAsErrors = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.mockito:mockito-core:5.15.2")
}
