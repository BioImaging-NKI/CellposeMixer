import java.time.LocalDateTime

plugins {
    // To optionally create a shadow/fat jar that bundles any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

qupathExtension {
    name = "CellposeMixer"
    group = "nl.nki.qupath"
    // Release builds (GitHub workflow) pass -PreleaseVersion=x.y.z, taken from the git tag vx.y.z
    version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0-SNAPSHOT")
    description = "Interactive channel mixing for Cellpose segmentation of multiplexed images in QuPath (NKI)"
    automaticModule = "nl.nki.qupath.extension.cellposemixer"
}

dependencies {
    // Provided by QuPath at runtime -> 'shadow' scope (not bundled into the jar)
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    // BIOP Cellpose extension: must be installed in QuPath by the user (we check at runtime).
    // Published by the QuPath team on maven.scijava.org; keep in sync with the installed version.
    compileOnly("io.github.qupath:qupath-extension-cellpose:0.12.1") { isTransitive = false }

    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher") // version from the JUnit BOM; required by Gradle 9
    // Real JavaFX jars (platform classifier) for UI tests such as ChannelRowFxTest.
    // UI tests are skipped on headless machines (e.g. GitHub Actions Linux runners).
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val fxClassifier = when {
        os.contains("win") -> "win"
        os.contains("mac") -> if (arch == "aarch64") "mac-aarch64" else "mac"
        else -> if (arch == "aarch64") "linux-aarch64" else "linux"
    }
    for (m in listOf("base", "graphics", "controls"))
        testImplementation("org.openjfx:javafx-$m:25.0.2:$fxClassifier")
}

// Stamp the build time into the jar so we can confirm in QuPath that the newest build is loaded
tasks.processResources {
    val buildTime = LocalDateTime.now().withNano(0).toString()
    inputs.property("buildTime", buildTime)
    filesMatching("**/build-info.properties") {
        expand("buildTime" to buildTime, "version" to project.version)
    }
}
