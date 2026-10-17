import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.radiolaria.bewegungslogger"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.radiolaria.bewegungslogger"
        minSdk = 29
        targetSdk = 35
        // Fortlaufende Build-Nummer aus GitHub Actions, lokal 1
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Fester Schlüssel im (privaten) Repo, damit Updates sich über die alte Version installieren lassen.
    signingConfigs {
        create("fixed") {
            storeFile = file("signing.jks")
            storePassword = "bewegungslogger"
            keyAlias = "logger"
            keyPassword = "bewegungslogger"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Keine Laufzeit-Abhängigkeiten außer der Kotlin-Standardbibliothek; kein Google.
    testImplementation("junit:junit:4.13.2")
}
