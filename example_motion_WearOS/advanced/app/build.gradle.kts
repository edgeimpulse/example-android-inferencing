plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.edgeimpulse.edgeimpulsewearos"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.edgeimpulse.edgeimpulsewearos"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")

    // Jetpack Compose for Wear OS
    implementation("androidx.wear.compose:compose-material:1.2.0")
    implementation("androidx.wear.compose:compose-foundation:1.2.0")

    // Compose UI
    implementation("androidx.compose.ui:ui:1.5.0")
    implementation("androidx.compose.foundation:foundation:1.5.0")
    implementation("androidx.compose.runtime:runtime:1.5.0")
    implementation("androidx.compose.material:material:1.5.0")

    // Wear OS Specific
    implementation("androidx.wear:wear:1.3.0")

    // Compose Preview & Debugging
    debugImplementation("androidx.compose.ui:ui-tooling:1.5.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")

    // Jetpack Compose for Wear OS
    implementation("androidx.wear.compose:compose-material:1.2.0")
    implementation("androidx.wear.compose:compose-foundation:1.2.0")

    // Compose UI
    implementation("androidx.compose.ui:ui:1.5.0")
    implementation("androidx.compose.foundation:foundation:1.5.0")
    implementation("androidx.compose.runtime:runtime:1.5.0")
    implementation("androidx.compose.material:material:1.5.0")

    // Splash screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Google Play Services for Wearables
    implementation(libs.play.services.wearable)

    // Compose BOM (Bill of Materials)
    implementation(platform(libs.compose.bom))

    // Compose UI Libraries
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.compose.material)
    implementation(libs.compose.foundation)

    // Wear OS Specific Libraries
    implementation(libs.wear.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)

    // Tiles and Watchface
    implementation(libs.tiles)
    implementation(libs.tiles.material)
    implementation(libs.tiles.tooling.preview)

    // Horologist Libraries for Wear OS
    implementation(libs.horologist.compose.tools)
    implementation(libs.horologist.tiles)

    // Watchface Complications
    implementation(libs.watchface.complications.data.source.ktx)

    // Testing Dependencies
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)

    // Debugging Dependencies
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.tiles.tooling)
}
