plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.phonebot.android"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.phonebot.android"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }
    buildFeatures { compose = true; aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" }
    }
    packaging { jniLibs { useLegacyPackaging = false } }
    lint {
        warningsAsErrors = true
        disable += "AndroidGradlePluginVersion"
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

val prepareRuntime by tasks.registering(Exec::class) {
    workingDir(rootDir)
    commandLine("node", "scripts/prepare-runtime.mjs")
    inputs.files(rootProject.fileTree("runtime") {
        exclude("node_modules/**", "dist/**")
    }, rootProject.file("scripts/prepare-runtime.mjs"), rootProject.file("scripts/prepare-brand.mjs"), rootProject.file("scripts/prepare-vector-icons.mjs"),
        rootProject.fileTree("docs/assets/providers"), rootProject.fileTree("docs/assets/icons"), rootProject.file("docs/assets/phonebot-black.svg"))
    outputs.dirs("src/main/assets/runtime", "src/main/jniLibs", "src/main/cpp/include")
    outputs.files("src/main/res/drawable/ic_phonebot_mark.xml", "src/main/res/drawable/ic_phonebot_foreground.xml",
        "src/main/res/mipmap-anydpi/ic_launcher.xml", "src/main/res/mipmap-anydpi-v33/ic_launcher.xml",
        "src/main/java/dev/phonebot/android/PhoneBotMark.kt", rootProject.file("docs/assets/phonebot-white.svg"), rootProject.file("docs/assets/phonebot-animated.svg"))
}
tasks.named("preBuild") { dependsOn(prepareRuntime) }
tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn(prepareRuntime)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("dev.chrisbanes.haze:haze:2.0.1")
    implementation("dev.chrisbanes.haze:haze-blur:2.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.46.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.46.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-coil3:0.46.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
