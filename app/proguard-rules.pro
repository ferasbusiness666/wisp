# --- WebRTC -----------------------------------------------------------------
# libwebrtc calls back into Java from native code by name; anything reachable
# only from JNI has to survive shrinking and renaming.
-keep class org.webrtc.** { *; }
-keep interface org.webrtc.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn org.webrtc.**

# --- OkHttp -----------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- App --------------------------------------------------------------------
# Keep the parcelable/serializable shapes used across the service boundary.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
