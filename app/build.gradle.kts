plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val releaseKeystore = providers.environmentVariable("YARDHAL_RELEASE_KEYSTORE").orNull
val releaseKeyPassword = providers.environmentVariable("YARDHAL_RELEASE_KEY_PASSWORD").orNull

android {
    namespace = "dev.brentdevs.yardhal"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.brentdevs.yardhal"
        minSdk = 33
        targetSdk = 35
        versionCode = providers.gradleProperty("yardhalVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("yardhalVersionName").orNull ?: "0.1.0"
    }

    signingConfigs {
        if (releaseKeystore != null && releaseKeyPassword != null) {
            create("yardhalRelease") {
                storeFile = file(releaseKeystore)
                storePassword = releaseKeyPassword
                keyAlias = "yardhal"
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystore != null && releaseKeyPassword != null) {
                signingConfig = signingConfigs.getByName("yardhalRelease")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(project(":core:protocol"))
    implementation(project(":core:client"))
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.security.crypto)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")
}
