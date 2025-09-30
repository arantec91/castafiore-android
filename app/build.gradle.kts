plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("kotlin-parcelize")
    id("androidx.navigation.safeargs.kotlin")
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" // Reemplaza kapt para Glide
}

// Pin Material to a published version in case transitive dependencies request a newer, unpublished one
val materialVersion = libs.versions.material.get()
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.google.android.material" && requested.name == "material") {
            useVersion(materialVersion)
            because("Pin Material to a published version")
        }
    }
}

android {
    namespace = "com.arantec.castafiore"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arantec.castafiore"
        minSdk = 30
        targetSdk = 36
        versionCode = 110
        versionName = "4.1.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore.jks")
            storePassword = "b9fdd76e18beead0649fcbf54978ee28"
            keyAlias = "b1b20160b9d7258d582e48a69c961446"
            keyPassword = "8dec9aec42d54c2b4353462e295a5fda"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {

    // Enforce Material version via constraints as a second line of defense
    constraints {
        implementation("com.google.android.material:material:$materialVersion") {
            because("AndroidX Navigation may request a newer Material; enforce known published version")
        }
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("androidx.activity:activity:1.8.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Navigation Component (use version catalog)
    // Removed explicit 2.7.6 to avoid mixing versions
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // SwipeRefreshLayout para pull-to-refresh
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Lifecycle Process para AppLifecycleManager
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.lifecycle:lifecycle-common:2.7.0")

    // Lifecycle KTX para ViewModel y LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    // Added: Lifecycle runtime KTX for lifecycleScope and coroutine integration
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WorkManager para descargas en segundo plano
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ExoPlayer for audio playback
    implementation("com.google.android.exoplayer:exoplayer:2.19.1")

    // Image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")
    ksp("com.github.bumptech.glide:ksp:4.16.0") // Para el módulo personalizado de Glide con KSP
    implementation("com.github.bumptech.glide:okhttp3-integration:4.16.0") // Integración con OkHttp

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // ViewPager2
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // Fragment KTX
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Palette
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Media notifications (MediaStyle)
    implementation("androidx.media:media:1.7.0")

    // Added: Kotlin Coroutines for Android (provides Dispatchers.Main, launch, etc.)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Shimmer for skeleton loading
    implementation("com.facebook.shimmer:shimmer:0.5.0")

    // Usar LrcView local como módulo
    implementation(project(":lrcview"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
