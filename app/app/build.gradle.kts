import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
}

android {
    namespace = "com.kovedash.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kovedash.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"

        // Mapbox public access token (pk.*) read from local.properties so we never
        // commit it. Exposed at runtime via BuildConfig.MAPBOX_PUBLIC_TOKEN.
        val mapboxPublic: String = run {
            val f = rootProject.file("local.properties")
            if (f.exists()) {
                val props = Properties()
                f.inputStream().use { props.load(it) }
                props.getProperty("MAPBOX_PUBLIC_TOKEN") ?: ""
            } else {
                System.getenv("MAPBOX_PUBLIC_TOKEN") ?: ""
            }
        }
        buildConfigField("String", "MAPBOX_PUBLIC_TOKEN", "\"$mapboxPublic\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.play.services.location)
    implementation(libs.mapbox.maps.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
