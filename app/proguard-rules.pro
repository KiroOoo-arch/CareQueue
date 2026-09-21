# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Firebase
-keep class com.carequeue.plus.data.model.** { *; }
-keep class com.google.firebase.** { *; }

# Keep Compose
-dontwarn androidx.compose.**
