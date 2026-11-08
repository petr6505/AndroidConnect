plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.androidconnect"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.androidconnect"
        minSdk = 29
        targetSdk = 37
        versionCode = 3
        versionName = "1.2.0"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            // Signed with the debug key so the APK installs directly; use your own key to publish.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
}
