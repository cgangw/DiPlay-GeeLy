// GD vehicle bridge: the separately installed read-only vehicle property provider for DiPlay.
plugins {
    alias(libs.plugins.android.application)
}

// The same artifact name as the upstream GD bridge.
val bridgeVersionName = "0.11.25"

android {
    namespace = "com.geely.desktop.vehicle.bridge"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.geely.desktop.vehicle.bridge"
        // Matches the head unit: the bridge talks to vendor services that require Android 9.
        minSdk = 28
        targetSdk = 30
        versionCode = 49
        versionName = bridgeVersionName
    }

    // The release key comes from the environment, like the Geely app, so no credential lives here.
    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        aidl = true
    }

    lint {
        // The bridge matches the head unit's platform target (Android 11), not Play requirements.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("GD-Vehicle-Bridge-$bridgeVersionName.apk")
        }
    }
}
