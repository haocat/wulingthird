import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "com.open.wuling"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.open.wuling"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Read sensitive config from local.properties (fallback to env vars for CI)
        fun prop(key: String): String {
            val localProps = rootProject.file("local.properties")
            if (localProps.exists()) {
                val props = Properties()
                props.load(localProps.inputStream())
                val value = props.getProperty(key, "").trim()
                if (value.isNotEmpty()) return value
            }
            return (System.getenv(key.replace(".", "_").uppercase()) ?: "").trim()
        }

        buildConfigField("String", "CLIENT_ID", "\"${prop("wuling.client.id")}\"")
        buildConfigField("String", "CLIENT_SECRET", "\"${prop("wuling.client.secret")}\"")
        buildConfigField("String", "APP_CODE", "\"${prop("wuling.app.code")}\"")
        buildConfigField("String", "APP_VERSION", "\"${prop("wuling.app.version")}\"")
        buildConfigField("String", "BASE_URL", "\"${prop("wuling.base.url")}\"")
        buildConfigField("String", "DEVICE_IMEI", "\"${prop("wuling.device.imei")}\"")
        buildConfigField("String", "DEVICE_MODEL", "\"${prop("wuling.device.model")}\"")
        buildConfigField("String", "DEVICE_BRAND", "\"${prop("wuling.device.brand")}\"")
        buildConfigField("String", "API_VERSION", "\"${prop("wuling.api.version")}\"")
        buildConfigField("String", "API_VERSION_CODE", "\"${prop("wuling.api.version.code")}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("WULING_KEYSTORE_PATH") ?: "../keystore/wuling.keystore")
            storePassword = System.getenv("WULING_KEYSTORE_PASSWORD") ?: "wuling123"
            keyAlias = System.getenv("WULING_KEY_ALIAS") ?: "wuling"
            keyPassword = System.getenv("WULING_KEY_PASSWORD") ?: "wuling123"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isShrinkResources = true
            isCrunchPngs = true

            val releaseSigningConfig = signingConfigs.getByName("release")
            signingConfig = if (releaseSigningConfig.storeFile?.exists() == true) {
                releaseSigningConfig
            } else {
                signingConfigs.getByName("debug")
            }
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.5")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.48")
    kapt("com.google.dagger:hilt-android-compiler:2.48")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // OkHttp + Gson (for API calls)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // DataStore (for Token persistence)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Coil (for image loading)
    implementation("io.coil-kt:coil-compose:2.5.0")

    // Conscrypt - modern TLS provider
    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
