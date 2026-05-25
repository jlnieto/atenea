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
        versionCode = 127
        versionName = "0.5.94"

        val ateneaApiBaseUrl = providers.gradleProperty("ATENEA_API_BASE_URL")
            .orElse("https://atenea.yudri.es")
            .get()
        val updateManifestUrl = providers.gradleProperty("ATENEA_ANDROID_UPDATE_MANIFEST_URL")
            .orElse("")
            .get()
        val firebaseApiKey = providers.gradleProperty("ATENEA_FIREBASE_API_KEY")
            .orElse("")
            .get()
        val firebaseProjectId = providers.gradleProperty("ATENEA_FIREBASE_PROJECT_ID")
            .orElse("")
            .get()
        val firebaseAppId = providers.gradleProperty("ATENEA_FIREBASE_APP_ID")
            .orElse("")
            .get()
        val firebaseGcmSenderId = providers.gradleProperty("ATENEA_FIREBASE_GCM_SENDER_ID")
            .orElse("")
            .get()
        buildConfigField("String", "ATENEA_API_BASE_URL", "\"${escapeBuildConfigString(ateneaApiBaseUrl)}\"")
        buildConfigField("String", "ATENEA_ANDROID_UPDATE_MANIFEST_URL", "\"${escapeBuildConfigString(updateManifestUrl)}\"")
        buildConfigField("String", "ATENEA_FIREBASE_API_KEY", "\"${escapeBuildConfigString(firebaseApiKey)}\"")
        buildConfigField("String", "ATENEA_FIREBASE_PROJECT_ID", "\"${escapeBuildConfigString(firebaseProjectId)}\"")
        buildConfigField("String", "ATENEA_FIREBASE_APP_ID", "\"${escapeBuildConfigString(firebaseAppId)}\"")
        buildConfigField("String", "ATENEA_FIREBASE_GCM_SENDER_ID", "\"${escapeBuildConfigString(firebaseGcmSenderId)}\"")
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
    implementation(libs.firebase.messaging)

    debugImplementation(libs.androidx.compose.ui.tooling)
}

fun escapeBuildConfigString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")
