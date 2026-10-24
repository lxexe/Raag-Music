plugins { id("com.android.test") }
android {
    namespace = "com.raagmusic.app.benchmarks"
    compileSdk = 35
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 28
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "benchmark"
        }
    }
    experimentalProperties["android.experimental.self-instrumenting"] = true
}
dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test:runner:1.6.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
