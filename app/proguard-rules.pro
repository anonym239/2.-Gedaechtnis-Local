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

# LiteRT-LM: JNI-Callbacks und per JSON (Gson) gelesene Klassen
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Flogger (von MediaPipe genutzt) lädt sein Backend per Reflection; ohne diese Regel scheitert
# die statische Initialisierung (ExceptionInInitializerError beim TextEmbedder).
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**
-keep class com.google.mediapipe.framework.** { *; }
