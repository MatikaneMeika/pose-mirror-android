plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.posemirror.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.posemirror.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // CameraX
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // MediaPipe Tasks Vision (PoseLandmarker) — matches PC mediapipe>=0.10.14
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // Background index updates (WorkManager) + settings (DataStore)
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
}

// Fetch the pose model bundle before building when it is not already there.
// CI runs tools/download_model.sh explicitly; this is a best-effort
// convenience for local builds (a missing `bash` never fails the build,
// the app then falls back to downloading the model at runtime).
val downloadPoseModel by tasks.registering(Exec::class) {
    onlyIf { !file("src/main/assets/pose_landmarker_full.task").exists() }
    commandLine("bash", rootProject.file("tools/download_model.sh").absolutePath)
    isIgnoreExitValue = true
}
tasks.named("preBuild") { dependsOn(downloadPoseModel) }
