import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "com.yoruc.mangacracker"
version = "1.0.10"

kotlin { jvmToolchain(21) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.androidx.navigation:navigation-compose:2.8.0-alpha10")
    implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.28.0")
    // the video player: libmpv (shipped in appResources/windows, not in git) through JNA
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.yoruc.mangacracker.MainKt"
        // Low memory (measured: Home ~250 MB, reading ~320 MB with these vs ~280 / ~360 MB with Java's defaults):
        // a 200 MB heap that shrinks back when idle, the serial collector (no GC threads), the quick C1 compiler only,
        // small thread stacks and code cache. Pictures are decoded at the size they are shown (48 MB picture cache).
        jvmArgs += listOf("-Xms16m", "-Xmx200m", "-XX:+UseSerialGC", "-Xss256k", "-XX:MaxMetaspaceSize=128m",
            "-XX:ReservedCodeCacheSize=32m", "-XX:TieredStopAtLevel=1", "-XX:CICompilerCount=1",
            "-XX:MinHeapFreeRatio=5", "-XX:MaxHeapFreeRatio=15", "-Dsun.java2d.d3d=false", "-Dfile.encoding=UTF-8",
            // plain IPv4 sockets: a VPN that tunnels only some apps (Proton split tunnelling) ties their connections to its
            // IPv4 address, which Java's default dual IPv6 / IPv4 sockets refuse ("Cannot assign requested address")
            "-Djava.net.preferIPv4Stack=true")
        nativeDistributions {
            appResourcesRootDir.set(project.layout.projectDirectory.dir("appResources"))
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "MangaCracker"
            packageVersion = "1.0.10"
            windows { menu = true; shortcut = true; perUserInstall = true; dirChooser = true; iconFile.set(project.file("appIcon/icon.ico")) }
            modules("java.net.http", "java.sql", "jdk.unsupported", "java.naming", "java.management", "jdk.management", "jdk.httpserver")
        }
    }
}

tasks.test { useJUnit(); testLogging { showStandardStreams = true }; maxHeapSize = "2g"; systemProperty("cutoutData", (project.findProperty("cutoutData") ?: "").toString()); systemProperty("sources", (project.findProperty("sources") ?: "").toString()) }
