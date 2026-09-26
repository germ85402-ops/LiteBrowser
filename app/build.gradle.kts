plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.svetlo"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.svetlo.browser"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    // Release key comes from the environment (CI secrets or a local shell); see docs/RELEASE.md.
    val releaseKeystore = System.getenv("SVETLO_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SVETLO_STORE_PASSWORD")
                keyAlias = System.getenv("SVETLO_KEY_ALIAS")
                keyPassword = System.getenv("SVETLO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a release key the APK is debug-signed so test builds stay installable.
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
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}

tasks.withType<Test>().configureEach {
    // Robolectric tests download a large Android runtime; run them only on request (-Pscreenshots).
    val screenshots = project.findProperty("screenshots")?.toString() ?: "false"
    systemProperty("screenshots", screenshots)
    if (screenshots == "false") {
        filter.excludeTestsMatching("*ScreenshotTest")
        filter.excludeTestsMatching("*RobolectricTest")
    }
    systemProperty("screenshots.dir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
    testLogging { showStandardStreams = true; events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
