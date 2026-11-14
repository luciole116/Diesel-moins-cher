plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.diesel.moinscher"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.diesel.moinscher"
        minSdk = 24
        targetSdk = 35
        // Numéro de version incrémenté automatiquement à chaque compilation GitHub
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "1.$run"
    }

    // Clé de signature fixe : les mises à jour s'installent par-dessus l'ancienne version
    signingConfigs {
        getByName("debug") {
            val ks = file("debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Aucune dépendance externe : uniquement le SDK Android (HttpURLConnection, org.json, LocationManager).
