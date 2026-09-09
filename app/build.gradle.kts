plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.sixoffive.androidmcp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sixoffive.androidmcp"
        minSdk = 26
        targetSdk = 33 // matches the A03s (Android 13); avoids Android-14 FGS-type enforcement for the MVP
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A real release key, if one exists. Read from ~/.gradle/gradle.properties or the
        // environment so nothing secret lands in the repo, and applied conditionally so a fresh
        // checkout without the key still builds.
        val storePath = (project.findProperty("ANDROIDMCP_KEYSTORE") as String?)
            ?: System.getenv("ANDROIDMCP_KEYSTORE")
        if (storePath != null && file(storePath).exists()) {
            create("release") {
                storeFile = file(storePath)
                storePassword = (project.findProperty("ANDROIDMCP_KEYSTORE_PASSWORD") as String?)
                    ?: System.getenv("ANDROIDMCP_KEYSTORE_PASSWORD")
                keyAlias = (project.findProperty("ANDROIDMCP_KEY_ALIAS") as String?)
                    ?: System.getenv("ANDROIDMCP_KEY_ALIAS") ?: "androidmcp"
                keyPassword = (project.findProperty("ANDROIDMCP_KEY_PASSWORD") as String?)
                    ?: System.getenv("ANDROIDMCP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // The reason this variant exists: a release build is not `debuggable`, so the process
            // is not jdwp-attachable. The debug build is — and both target devices necessarily
            // have USB debugging on, because Shizuku requires it, so anything with adb could
            // attach and drive the app past its own gate.
            // OFF, deliberately: R8 drops Netty's @Sharable marker and HTTPS then dies after
            // exactly one request, while the UI still reports the server as running. The full
            // investigation, and the rules to start from if it is ever retried, are in
            // proguard-rules.pro. Minification was never the point of this variant — `debuggable`
            // was, and that comes from the build type.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Falls back to the debug key when no release key is configured. That is not a
            // compromise on the thing this variant is for: `debuggable` comes from the build
            // TYPE, not the signing key, so a debug-signed release build still closes the hole —
            // and keeping the signature means it installs over the existing app without an
            // uninstall, which would wipe tokens, SAF grants and special-access approvals.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    testOptions {
        unitTests {
            // Deliberately NOT `isReturnDefaultValues = true`. Stubbing android.jar to return
            // zeros would silently hand tests a null token from TokenStore.generate and a null
            // nonce from MediaStore.put — the tests would pass while asserting nothing. Anything
            // that genuinely needs a device stays out of this source set.
            isReturnDefaultValues = false

            // Forward the opt-in for SdkProgressHarnessTest into the FORKED test JVM. Without
            // this, `-Dandroidmcp.sdk=1` sets the property on the Gradle daemon only, the test's
            // `assumeTrue` never sees it, and the harness reports as skipped while looking for
            // all the world like it ran.
            all {
                it.systemProperty("androidmcp.sdk", System.getProperty("androidmcp.sdk") ?: "")
            }
        }
    }
    packaging {
        resources {
            // Ktor / kotlinx pull in duplicate metadata files; drop them.
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
            // jansi (pulled in by ktor-server-core) ships Windows .dll and macOS .jnilib blobs,
            // and GraalVM native-image metadata — none of it reachable on Android.
            excludes += "/org/fusesource/jansi/internal/native/**"
            excludes += "/META-INF/native-image/**"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.tls.certs)
    implementation(libs.ktor.server.netty)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mockito.core)

    // Instrumentation tests. Deliberately NOT wired into `check` — see app/src/androidTest.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
