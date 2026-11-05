plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.audiobook"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.audiobook"
        minSdk = 24
        targetSdk = 34

        // Automatic versioning based on GitHub Actions run number (e.g., 1.0.15, 1.0.16)
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 100 + runNumber
        versionName = "1.0.$runNumber"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
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

    composeOptions {
        kotlinCompilerExtensionVersion = when (org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION) {
            "1.9.0" -> "1.5.1"
            "1.9.10" -> "1.5.3"
            "1.9.20" -> "1.5.4"
            "1.9.21" -> "1.5.7"
            "1.9.22" -> "1.5.8"
            "1.9.23" -> "1.5.11"
            "1.9.24" -> "1.5.14"
            "1.9.25" -> "1.5.15"
            else -> "1.5.8"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("androidx.compose.material:material-icons-extended")

    // Media3 ExoPlayer & Session for background playback & notifications
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-session:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")

    // ExifInterface for photo rotation handling
    implementation("androidx.exifinterface:exifinterface:1.3.6")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
// END OF FILE