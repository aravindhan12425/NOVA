# Only used if minification is turned on for release builds.
# Vosk talks to its native library through JNA, which needs these classes intact.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-keep class ai.onnxruntime.** { *; }
