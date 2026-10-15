plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.squatalarm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.squatalarm.app"
        minSdk = 26
        targetSdk = 35
        // versionCode fijo: cualquier build se instala encima de otra sin desinstalar (y sin perder parámetros).
        versionCode = 1
        versionName = "1.0-build" + (System.getenv("GITHUB_RUN_NUMBER") ?: "local")
    }

    // Una sola firma (clave personal incluida en el repo) para que cualquier build —GitHub Actions,
    // Android Studio, debug o release— se instale encima del anterior sin desinstalar.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/squatalarm.jks")
            storePassword = "squatalarm"
            keyAlias = "squatalarm"
            keyPassword = "squatalarm"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
