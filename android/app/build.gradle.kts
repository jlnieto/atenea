plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.atenea.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.atenea.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 115
        versionName = "0.5.82"

        val ateneaApiBaseUrl = providers.gradleProperty("ATENEA_API_BASE_URL")
            .orElse("https://atenea.yudri.es")
            .get()
        val updateManifestUrl = providers.gradleProperty("ATENEA_ANDROID_UPDATE_MANIFEST_URL")
            .orElse("")
            .get()
        buildConfigField("String", "ATENEA_API_BASE_URL", "\"${escapeBuildConfigString(ateneaApiBaseUrl)}\"")
        buildConfigField("String", "ATENEA_ANDROID_UPDATE_MANIFEST_URL", "\"${escapeBuildConfigString(updateManifestUrl)}\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":api"))
    implementation(project(":secure"))
    implementation(project(":core-console"))
    implementation(project(":voice-runtime"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

fun escapeBuildConfigString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
