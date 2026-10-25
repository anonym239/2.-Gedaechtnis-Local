# MediaPipe Tasks (GenAI + Text) nutzen JNI und Protobuf-Reflection.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.auto.value.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**

# ML Kit Text Recognition
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Room-Entities
-keep class de.gedaechtnis.local.data.db.** { *; }
