# Add project-specific ProGuard rules here.
-dontwarn androidx.media.**
-dontwarn com.google.android.gms.**
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# androidx.security:security-crypto nutzt Google Tink; Tink registriert Primitives per Reflection.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# NewPipeExtractor: Site-Extraktoren, eingebettetes protobuf-javalite und die Rhino-JS-Engine
# nutzen Reflection an mehreren Stellen; ein breiter Keep ist hier der sichere Ansatz.
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn org.mozilla.javascript.**
-dontwarn org.jsoup.**
-dontwarn com.google.protobuf.**
-dontwarn javax.script.**

# org.apache.httpcomponents:httpclient (transitiv über google-http-client-apache-v2) referenziert
# Desktop-JRE-only-Klassen (Kerberos/LDAP-Hostname-Verifizierung), die auf Android nicht
# existieren. Diese Codepfade werden von der App nicht genutzt (eigene HTTP-Aufrufe laufen über
# NetHttpTransport bzw. OkHttp), daher sind sie für R8 sicher zu ignorieren.
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**

# Unofficial aauto-sdk (OEM projection): reflection + GMS car stubs.
-keep class com.google.android.apps.auto.sdk.** { *; }
-keep class com.google.android.gms.car.** { *; }
-keep class android.support.car.** { *; }
-dontwarn com.google.android.apps.auto.sdk.**
-dontwarn com.google.android.gms.car.**
-dontwarn android.support.car.**
-dontwarn javax.annotation.concurrent.GuardedBy
-dontwarn com.google.android.gms.common.internal.Hide
-dontwarn com.google.android.gms.common.util.VisibleForTesting

# Android Auto aauto projection host instantiates the returned CarActivity class via
# Class.getConstructor().newInstance(). R8 cannot see that reflective constructor use and may
# remove the otherwise-unused public no-arg constructor in release builds.
-keep class de.uni.youtubeaa.car.VideoCarActivity {
    public <init>();
    *;
}

# Projection service is discovered/entered by Android Auto / the vendored aauto SDK.
-keep class de.uni.youtubeaa.car.CarProjectionService { *; }
