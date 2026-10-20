plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

import java.util.Properties

val keystoreProperties = Properties().apply {
    val file = rootProject.file("key.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun signingValue(key: String, envKey: String): String? {
    val fromProperties = keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }
    val fromEnv = System.getenv(envKey)?.takeIf { it.isNotBlank() }
    return fromProperties ?: fromEnv
}

val releaseStoreFilePath = signingValue("storeFile", "CLEARPDF_UPLOAD_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "CLEARPDF_UPLOAD_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "CLEARPDF_UPLOAD_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "CLEARPDF_UPLOAD_KEY_PASSWORD")

// F-Droid builds one APK per CPU (its recipe passes -PfdroidAbi=<abi>, one build block each). Every
// APK keeps only that ABI's native libraries and gets its own versionCode, 10 x versionCode + n, in
// the order F-Droid asks for: armeabi-v7a < arm64-v8a < x86 < x86_64.
val fdroidAbis = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
val fdroidAbi = (findProperty("fdroidAbi") as String?)?.trim()?.takeIf { it in fdroidAbis }

val hasReleaseSigning = !releaseStoreFilePath.isNullOrBlank() &&
    !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() &&
    !releaseKeyPassword.isNullOrBlank()

// A missing key must fail a release artifact build, but not debug/test/lint builds. Aggregate
// tasks (`assemble`, `build`, `bundle`, ...) can produce release artifacts even when the task name
// never contains "Release", so they are guarded too. Debug-specific tasks remain unaffected.
val releaseArtifactRequested = gradle.startParameter.taskNames.any { task ->
    val name = task.substringAfterLast(':').lowercase()
    val aggregate = name in setOf("assemble", "build", "bundle", "install", "publish", "package")
    // Tasks that produce an installable/publishable artifact, including flavor aggregates such as
    // `assemblePlay` or `bundlePlay`, which also build the release variant. Test/lint/compile tasks
    // are not artifact builds and must not require a signing key.
    val artifactTask = listOf("assemble", "bundle", "package", "install", "publish")
    val flavorAggregate = artifactTask.any { name.startsWith(it) } &&
        !name.contains("debug") &&
        !name.contains("test")
    val releaseArtifact = name.contains("release") &&
        !name.contains("test") &&
        artifactTask.any { name.startsWith(it) }
    val signRelease = name.startsWith("sign") && name.contains("release")
    aggregate || flavorAggregate || releaseArtifact || signRelease
}

android {
    namespace = "com.hmx.pdf"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "com.hmx.pdf"
        minSdk = 23
        targetSdk = 36
        // Keep this a literal: F-Droid's update checker reads it from this file.
        versionCode = 6
        versionName = "2.1.1"
        // Keep every locale declared by the app. Filtering this to English
        // removes values-pt-rBR from the packaged APK, so the language picker
        // can appear to work while the app continues to resolve English.
    }

    signingConfigs {
        getByName("debug") {
            // Default debug keystore
        }
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else if (releaseArtifactRequested) {
                throw GradleException(
                    "Release signing is not configured. Set CLEARPDF_UPLOAD_STORE_FILE, " +
                        "CLEARPDF_UPLOAD_STORE_PASSWORD, CLEARPDF_UPLOAD_KEY_ALIAS and " +
                        "CLEARPDF_UPLOAD_KEY_PASSWORD (or key.properties) before building a release artifact."
                )
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            vcsInfo.include = false
        }
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // Plain JVM unit tests (the xlsx model/writer). Android stubs return defaults instead of
        // throwing so incidental framework calls don't fail a pure-logic test.
        unitTests.isReturnDefaultValues = true
    }

    // Two distributions of the same app (identical applicationId):
    //  - play: Google Play. The optional Office engine ships as the on-demand dynamic feature
    //    module :office_engine (Play Feature Delivery); no native code is ever downloaded by the app.
    //  - foss: GitHub/F-Droid-style sideload builds. INTERNET is used for the optional Office
    //    engine download and the user-entered URL-to-PDF flow; see src/foss/AndroidManifest.xml.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("foss") {
            dimension = "distribution"
        }
        // F-Droid: free software only. No Google Play services / ML Kit (the document scanner
        // falls back to the camera app, OCR to Tesseract, background removal is not offered) and
        // no downloaded Office engine. Same applicationId, so it updates like the GitHub build.
        create("fdroid") {
            dimension = "distribution"
            if (fdroidAbi != null) {
                versionCode = 10 * (defaultConfig.versionCode ?: 0) + fdroidAbis.indexOf(fdroidAbi) + 1
                ndk { abiFilters += fdroidAbi }
            }
        }
    }
    // Code that needs Google's libraries lives in src/gms, shared by the play and foss builds;
    // the fdroid build has its own free implementations in src/fdroid.
    sourceSets {
        getByName("play").kotlin.srcDir("src/gms/java")
        getByName("foss").kotlin.srcDir("src/gms/java")
    }
    // AGP can't link a dynamic feature against ABI-split APK outputs, and the feature is only
    // ever delivered through a Play bundle anyway — so register it for bundle builds only.
    // APK builds (sideload/foss) simply don't contain it; the play installer then falls back.
    if (gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }) {
        dynamicFeatures += setOf(":office_engine")
    }
    // Bundling on-device OCR (bundled ML Kit + Tesseract4Android) added native .so libs for
    // 4 CPU architectures; without splitting, every install carries all 4. This produces one
    // APK per ABI (~1/4 the native-lib weight each) plus a universal fallback for sideloading.
    // Play Store distribution via an Android App Bundle (`./gradlew bundleRelease`) already does
    // this automatically and needs no config here — this `splits` block only matters for raw
    // APK builds/installs (`assembleDebug`/`assembleRelease`, `installDebug`, sideloading).
    splits {
        abi {
            // F-Droid builds and signs one APK per version itself, so its builds skip the split.
            isEnable = gradle.startParameter.taskNames.none { it.contains("fdroid", ignoreCase = true) }
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    packaging {
        resources {
            excludes += arrayOf(
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "META-INF/*.version",
                "META-INF/**/LICENSE.txt",
                // PdfBox-Android bundles these and they collide with other deps.
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.md"
            )
        }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    lint {
        checkReleaseBuilds = true
        // Existing debt is recorded once; new lint errors still fail the build.
        baseline = file("lint-baseline.xml")
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xlambdas=class"
        )
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(libs.androidx.core.ktx)
    // Per-app language (LocaleHelper). Declared directly: the play / foss builds used to get it
    // only transitively through Google's libraries, which the F-Droid build doesn't have.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material.ripple)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kyant.shapes)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":backdrop"))
    implementation(project(":pdf-core"))
    implementation(project(":ocr-core"))
    // Apache POI provides legacy .doc/.xls/.ppt text extraction. It is Apache-2.0
    // licensed; see THIRD_PARTY_NOTICES.md for redistribution requirements.
    implementation("org.apache.poi:poi:3.17")
    implementation("org.apache.poi:poi-scratchpad:3.17")

    // PdfBox-Android — needed by PdfViewerViewModel for native PDF overlay export
    implementation(libs.pdfbox.android)

    // ML Kit Document Scanner & Camera
    "playImplementation"(libs.play.services.mlkit.scanner)
    "fossImplementation"(libs.play.services.mlkit.scanner)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.coil.compose)
    implementation(libs.acccompanist.permissions)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Image editor: GPUImage (upstream 2.1.0, ~0.2 MB; the T8RIN 8.x fork needs compileSdk 37
    // + desugaring and drags in androidx.core 1.19) for GL adjustments/filters, EXIF read/strip,
    // and ML Kit subject segmentation for auto background removal (API 24+, gated at runtime;
    // isolated behind BackgroundRemover so a FOSS flavor can swap it out).
    implementation(libs.gpuimage)
    implementation(libs.androidx.exifinterface)
    "playImplementation"(libs.mlkit.subject.segmentation)
    "fossImplementation"(libs.mlkit.subject.segmentation)
    "playImplementation"(libs.mlkit.text.recognition)
    "fossImplementation"(libs.mlkit.text.recognition)
    // A real XmlPullParser for JVM tests; android.jar only carries stubs of it.
    testImplementation("net.sf.kxml:kxml2:2.3.0")

    // Optional Office engine (powered by LibreOffice) — installer shared by all distributions.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.xz)
    "playImplementation"(libs.play.feature.delivery.ktx)
}
