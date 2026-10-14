plugins {
    id("com.android.library")
}

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "de.uni.youtubeaa.car"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        buildConfigField(
            "String",
            "YOUTUBE_TV_CLIENT_ID",
            providers.gradleProperty("YOUTUBE_TV_CLIENT_ID").orElse("").get().asBuildConfigString()
        )
        buildConfigField(
            "String",
            "YOUTUBE_TV_CLIENT_SECRET",
            providers.gradleProperty("YOUTUBE_TV_CLIENT_SECRET").orElse("").get().asBuildConfigString()
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // :car wird über project(":car") in :app eingebunden; die tatsächliche Minifizierung
            // (R8) läuft einmalig im finalen App-Build. consumerProguardFiles sorgt dafür, dass
            // die hier definierten Keep-Regeln (Reflection-lastige Bibliotheken) dabei angewendet
            // werden.
            consumerProguardFiles("proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.media:media:1.8.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.appcompat:appcompat:1.8.0")


    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-datasource:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    // Unofficial OEM projection SDK (vendored Fermata aauto.aar; see libs/README.md).
    // Via :aauto wrapper — direct files("*.aar") breaks :car AAR/lint packaging under AGP 8+.
    implementation(project(":aauto"))

    // Stream-URL-Extraktion: Die YouTube Data API v3 liefert keine abspielbaren Stream-URLs
    // (nur Metadaten). NewPipeExtractor extrahiert die tatsächlichen Audio/Video-Stream-URLs
    // (wie im Task-Brief vorgegeben; nicht Teil der offiziellen Google-API).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
}
