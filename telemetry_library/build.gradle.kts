// telemetry_library/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") // ⬅️ required in Kotlin 2.0+
    id("maven-publish")
}

android {
    namespace = "com.androidtel.telemetry_library"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // Java 11 is the safest for compatibility
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
//        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        // ✅ leave compose enabled, but don’t pin compiler version here
        compose = true
    }

    // ❌ Don’t set composeOptions.kotlinCompilerExtensionVersion
    // (the consuming app should control it)

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        warningsAsErrors = false
        disable.add("NullSafeMutableLiveData")
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {
    // Compose BOM — let apps override if needed
    api(platform(libs.androidx.compose.bom))

    // AndroidX core + UI
    api(libs.androidx.core.ktx)
    api(libs.androidx.appcompat)
    api(libs.material)

    // Lifecycle + Navigation
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.androidx.lifecycle.process)
    api(libs.androidx.navigation.runtime.android)
    api(libs.androidx.navigation.compose)

    // Compose UI
    api(libs.androidx.activity.compose)
    api(libs.androidx.ui)
    api(libs.androidx.ui.graphics)
    api(libs.androidx.ui.tooling.preview)
    api(libs.androidx.material3)

    // Networking + JSON
    api("com.google.code.gson:gson:2.10.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Desugaring
//    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("com.android.tools:desugar_jdk_libs:2.0.4")

    // ❌ REMOVE kotlin-stdlib from compileOnly — let apps provide it
    // compileOnly("org.jetbrains.kotlin:kotlin-stdlib")
    // compileOnly("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // Tests
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}


// JitPack publishing
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.KiplangatSang"
                artifactId = "android_telemetry"
                version = "1.0.14"
            }
        }
    }
}
