buildscript {
    repositories { google(); mavenCentral() }
    dependencies {
        if (providers.gradleProperty("firebaseAnalytics").orNull == "true") {
            classpath("com.google.gms:google-services:4.5.0")
        }
    }
}
plugins { id("com.android.application"); id("org.jetbrains.kotlin.plugin.compose") }
val firebaseAnalyticsEnabled = providers.gradleProperty("firebaseAnalytics").orNull == "true"
if (firebaseAnalyticsEnabled) {
    require(file("google-services.json").isFile) { "Firebase builds require app/google-services.json for com.raagmusic.app" }
    apply(plugin = "com.google.gms.google-services")
}
android {
    namespace = "com.raagmusic.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.raagmusic.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 36
        versionName = "1.24.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main") {
        kotlin.directories += if (firebaseAnalyticsEnabled) "src/firebase/java" else "src/noFirebase/java"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipe uses NIO APIs unavailable on Android versions below API 33.
        isCoreLibraryDesugaringEnabled = true
    }
    buildTypes {
        release { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
        create("preview") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            // Installable optimized build for performance verification and local previews.
        }
        create("benchmark") {
            initWith(getByName("preview"))
            isMinifyEnabled = false
            matchingFallbacks += "release"
        }
    }
}
dependencies {
    if (firebaseAnalyticsEnabled) {
        implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
        implementation("com.google.firebase:firebase-analytics")
    }
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.3")
    // NewPipe's public player-only helper exposes this runtime dependency in its return type.
    implementation("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-database:1.4.1")
    androidTestImplementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
