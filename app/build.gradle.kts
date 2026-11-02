import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Version numbers live in gradle.properties so a local build and CI report the
// same thing. They are bumped by hand for each release (releases are built and
// signed on the owner's PC; CI only builds and tests).
fun stringProperty(name: String, fallback: String): String =
    providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() } ?: fallback

fun intProperty(name: String, fallback: Int): Int {
    val raw = providers.gradleProperty(name).orNull ?: return fallback
    return raw.toIntOrNull() ?: throw GradleException("$name must be an integer (got '$raw')")
}

val appVersionName = stringProperty("mangacracker.versionName", "1.6.0")
val appVersionCode = intProperty("mangacracker.versionCode", 8)

// -Pmangacracker.dev=true (CI builds): a separate "MangaCracker Dev" app that installs next to the real one,
// so a test build never has to replace the real app (and its library). It never checks for app updates.
val devBuild = providers.gradleProperty("mangacracker.dev").orNull == "true"

android {
    namespace = "com.yoruc.mangacracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yoruc.mangacracker"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        if (devBuild) applicationIdSuffix = ".dev"
        manifestPlaceholders["appLabel"] = if (devBuild) "MangaCracker Dev" else "MangaCracker"
        buildConfigField("boolean", "DEV", devBuild.toString())

        // Every string in the app is English (the UI text is written in Compose),
        // so the translations that AndroidX, Media3 and the other libraries ship
        // are dead weight in resources.arsc. Keeping only English removes them
        // without changing anything the app can display.
        resConfigs("en")
    }

    // A personal key kept next to the project (keystore/, git-ignored), so updates install over the old app.
    // keystore/keystore.properties holds storePassword / keyAlias / keyPassword. The real key never goes to
    // GitHub: CI signs its builds with a throwaway key passed in through the MANGA_* environment variables.
    val keyProps = Properties()
    rootProject.file("keystore/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { keyProps.load(it) }

    fun secret(env: String, key: String): String? =
        (System.getenv(env) ?: keyProps.getProperty(key))?.takeIf { it.isNotBlank() }

    val keyStoreFile = System.getenv("MANGA_KEYSTORE")?.let { rootProject.file(it) }
        ?: rootProject.file("keystore/mangacracker.jks")
    val storePasswordValue = secret("MANGA_STORE_PASSWORD", "storePassword")
    val keyAliasValue = secret("MANGA_KEY_ALIAS", "keyAlias")
    val keyPasswordValue = secret("MANGA_KEY_PASSWORD", "keyPassword")
    val hasKey = keyStoreFile.exists() &&
        storePasswordValue != null && keyAliasValue != null && keyPasswordValue != null

    signingConfigs {
        if (hasKey) create("personal") {
            storeFile = keyStoreFile
            storePassword = storePasswordValue
            keyAlias = keyAliasValue
            keyPassword = keyPasswordValue
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasKey) signingConfigs.getByName("personal") else signingConfigs.getByName("debug")
        }
        debug {
            if (hasKey) signingConfig = signingConfigs.getByName("personal")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"

        // Never read at runtime: licence/readme files, build metadata, Kotlin
        // reflection tables (this app has no kotlin-reflect) and the coroutine
        // debug probe. Dropping them saves a little space and a lot of noise.
        resources.excludes += setOf(
            "/META-INF/*.md",
            "/META-INF/version-control-info.textproto",
            "/DebugProbesKt.bin",
            "/kotlin/**",
        )

        // minSdk is 29, so AGP's default is to store classes.dex uncompressed
        // ("If null, dex files will be uncompressed when minSdk >= 28"). That
        // trades download size for install time: 4.8 MB of dex sat uncompressed
        // in a 5.3 MB APK, so every update downloaded ~2x the bytes it needed to.
        // This app is sideloaded and updates itself over GitHub, where the
        // download is the cost a user actually notices, so use the legacy
        // (compressed) packaging mode. Deflate brings the dex to ~2.4 MB.
        dex { useLegacyPackaging = true }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.systemProperty("robolectric.graphicsMode", "NATIVE"); it.systemProperty("roborazzi.test.record", "true"); it.maxHeapSize = "3g"; it.systemProperty("sources", (project.findProperty("sources") ?: "").toString()) }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // video player (MP4 / WebM / HLS with AES keys), subtitles, clip export for "Record"
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.work:work-testing:2.10.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
