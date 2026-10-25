import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Versionsangaben können von der CI überschrieben werden (z. B. aus dem Release-Tag "v1.2.3").
val appVersionName: String = (findProperty("versionName") as String?) ?: "1.0.0"
val appVersionCode: Int = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "de.gedaechtnis.local"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.gedaechtnis.local"
        // API 31 (Android 12): Voraussetzung der LiteRT-LM-Laufzeit; deckt auch takeScreenshot (API 30) ab.
        minSdk = 31
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // LiteRT-LM liefert native Bibliotheken nur für 64-Bit-ARM (und x86_64 für Emulatoren).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    signingConfigs {
        // Release-Signatur über Umgebungsvariablen (GitHub Secrets). Fehlen sie, wird mit dem
        // Debug-Key signiert, damit die Release-APK trotzdem installierbar ist.
        create("release") {
            val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            } else {
                val debug = getByName("debug")
                storeFile = debug.storeFile
                storePassword = debug.storePassword
                keyAlias = debug.keyAlias
                keyPassword = debug.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // TFLite-Modelle unkomprimiert ablegen, damit MediaPipe sie per mmap laden kann (spart RAM).
    androidResources { noCompress += listOf("tflite", "task") }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.pickFirsts += "**/liblitertlm_jni.so"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Lokale Datenbank inkl. FTS4-Volltextindex
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // On-Device-KI: OCR (ML Kit), Text-Embeddings (MediaPipe), Sprachmodelle (LiteRT-LM)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mediapipe.tasks.text)
    implementation(libs.litertlm)

    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------------------------
// Embedding-Modell (Universal Sentence Encoder, ~6 MB) automatisch in die Assets laden.
// So muss niemand manuell etwas installieren; die App funktioniert ohne Modell im Volltext-Modus.
// ---------------------------------------------------------------------------------------------
val embeddingModelUrl =
    "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite"

val downloadEmbeddingModel by tasks.registering {
    val target = layout.projectDirectory.file("src/main/assets/universal_sentence_encoder.tflite")
    outputs.file(target)
    doLast {
        val file = target.asFile
        if (file.exists() && file.length() > 0) return@doLast
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.part")
        try {
            URI(embeddingModelUrl).toURL().openStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            tmp.renameTo(file)
            logger.lifecycle("Embedding-Modell geladen: ${file.length() / 1024} KB")
        } catch (e: Exception) {
            tmp.delete()
            logger.warn("Embedding-Modell konnte nicht geladen werden (${e.message}). Semantische Suche ist deaktiviert.")
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadEmbeddingModel) }
