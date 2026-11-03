plugins {
    id("com.android.application")
}

val signingStore = System.getenv("X9U_SIGNING_STORE")

android {
    namespace = "dev.koaan.x9uflasher"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.koaan.x9uflasher"
        minSdk = 29
        targetSdk = 36
        versionCode = 304
        versionName = "0.3.4"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        if (!signingStore.isNullOrBlank()) {
            create("releaseKey") {
                storeFile = file(signingStore)
                storePassword = System.getenv("X9U_SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("X9U_SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("X9U_SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (!signingStore.isNullOrBlank()) {
                signingConfigs.getByName("releaseKey")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
