plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.safetap"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.safetap"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            // Kotlin multiplatform metadata duplicates across androidx jars; not needed on Android
            excludes += "nonJvmMain/**"
            excludes += "commonMain/**"
            excludes += "jvmMain/**"
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/kotlin-project-structure-metadata.json"
        }
    }
}

dependencies {
    // collection-ktx 1.2.0 duplicates classes now in collection 1.4+; drop the old one
    configurations.configureEach {
        exclude(group = "androidx.collection", module = "collection-ktx")
    }

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Compose UI for the main app screens
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Encrypted storage for the auth token, PIN hash, contacts, medical profile
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // PIN-or-biometric gate on the false-alarm button
    implementation("androidx.biometric:biometric:1.1.0")

    // GPS fixes for the SOS flow and the 15-second location stream
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
