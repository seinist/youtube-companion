# Add project-specific ProGuard rules here.
-dontwarn androidx.media.**
-dontwarn com.google.android.gms.**
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# Android Auto projection is reflection-driven. Keep the projected CarActivity and its public
# no-arg constructor in the final application shrink step as well (defence in depth in case
# library consumer rules are not merged by a particular AGP/R8 setup).
-keep class de.uni.youtubeaa.car.VideoCarActivity {
    public <init>();
    *;
}
-keep class de.uni.youtubeaa.car.CarProjectionService { *; }
