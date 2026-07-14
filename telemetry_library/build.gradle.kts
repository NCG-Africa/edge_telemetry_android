// telemetry_library/build.gradle.kts
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Compose compiler plugin not needed for Kotlin 1.8.x
    id("maven-publish")
}

// Single source of truth for the SDK version (surfaces in BuildConfig.SDK_VERSION headers).
val sdkVersion = "1.2.5-java8"

android {
    namespace = "com.androidtel.telemetry_library"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildFeatures {
        buildConfig = true
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
        // Java 8 compatibility with desugaring
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Compose disabled for Java 8 compatibility
    // buildFeatures {
    //     compose = true
    // }

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
    // Compose BOM removed for Java 8 compatibility
    // api(platform(libs.androidx.compose.bom))

    // AndroidX core + UI
    api(libs.androidx.core.ktx)
    api(libs.androidx.appcompat)
    api(libs.material)

    // Lifecycle + Navigation
    api(libs.androidx.lifecycle.runtime.ktx)
    api(libs.androidx.lifecycle.process)
    api(libs.androidx.navigation.runtime)
    // api(libs.androidx.navigation.compose) // Removed for Java 8

    // Compose UI removed for Java 8 compatibility
    // api(libs.androidx.activity.compose)
    // api(libs.androidx.ui)
    // api(libs.androidx.ui.graphics)
    // api(libs.androidx.ui.tooling.preview)
    // api(libs.androidx.material3)

    // Networking + JSON
    api("com.google.code.gson:gson:2.10.1")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // WorkManager for retry scheduling
    api("androidx.work:work-runtime-ktx:2.8.1")
    
    // Room for offline storage
    api("androidx.room:room-runtime:2.5.2")
    api("androidx.room:room-ktx:2.5.2")

    // Desugaring - enables Java 8+ APIs on older Android versions
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:1.2.2")

    // ❌ REMOVE kotlin-stdlib from compileOnly — let apps provide it
    // compileOnly("org.jetbrains.kotlin:kotlin-stdlib")
    // compileOnly("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // Tests
    testImplementation(libs.junit)
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("io.mockk:mockk-android:1.13.8")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // Compose test dependencies removed for Java 8 compatibility
    // androidTestImplementation(libs.androidx.ui.test.junit4)
    // debugImplementation(libs.androidx.ui.tooling)
    // debugImplementation(libs.androidx.ui.test.manifest)
}


// JitPack publishing
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.NCG-Africa"
                artifactId = "edge_telemetry_android"
                version = sdkVersion
            }
        }
    }
}
