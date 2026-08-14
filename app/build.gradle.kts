plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}

android {
    namespace = "io.github.zvensmoluya.vortexcue"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.zvensmoluya.vortexcue.prototype"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

configurations.configureEach {
    if (name.endsWith("UnitTestCompileClasspath") || name.endsWith("UnitTestRuntimeClasspath")) {
        exclude(group = "androidx.room3", module = "room3-runtime-android")
        exclude(group = "androidx.room3", module = "room3-paging-android")
        exclude(group = "androidx.room3", module = "room3-testing-android")
        exclude(group = "androidx.sqlite", module = "sqlite-android")
        exclude(group = "androidx.sqlite", module = "sqlite-async-android")
        exclude(group = "androidx.sqlite", module = "sqlite-framework-android")
        exclude(group = "androidx.sqlite", module = "sqlite-bundled-android")
    }
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.room3.runtime)
    implementation(libs.room3.paging)
    implementation(libs.sqlite.framework)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    ksp(libs.room3.compiler)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.datastore.core.jvm)
    testImplementation(libs.datastore.core.okio.jvm)
    testImplementation(libs.datastore.preferences.core.jvm)
    testImplementation(libs.room3.runtime.jvm)
    testImplementation(libs.room3.paging.jvm)
    testImplementation(libs.room3.testing.jvm)
    testImplementation(libs.sqlite.core.jvm)
    testImplementation(libs.sqlite.async.jvm)
    testImplementation(libs.sqlite.bundled.jvm)
    testImplementation(libs.robolectric)
}

