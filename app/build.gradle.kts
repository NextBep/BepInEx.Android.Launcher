plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Increment once for an assemble/build invocation, not for help or clean-only
// commands, and persist the CI number beside the project.
val ciVersionFile = rootProject.file("ci-version.txt")
val isBuildInvocation = gradle.startParameter.taskNames.any {
    it.contains("assemble", ignoreCase = true) || it.contains("build", ignoreCase = true)
}
val storedCiBuildNumber = ciVersionFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 1
val ciBuildNumber = if (isBuildInvocation) {
    (storedCiBuildNumber + 1).also { ciVersionFile.writeText(it.toString()) }
} else {
    storedCiBuildNumber
}

android {
    namespace = "com.bepinex.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bepinex.android"
        minSdk = 28
        targetSdk = 35
        versionCode = ciBuildNumber
        versionName = "1.0.0-ci.$ciBuildNumber"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-28"
                )
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
            }
        }
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

    ndkVersion = "27.0.12077973"

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            isJniDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Compose BOM - manages all Compose library versions
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Activity & Core
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // AppCompat (DayNight theme support)
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Pine — ART Java hook framework (FusionCore approach)
    implementation("top.canyie.pine:core:0.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
