# NanoHTTPD is reached directly from Kotlin, but retaining its public surface keeps
# release builds predictable across R8 versions.
-keep class fi.iki.elonen.** { *; }
-dontwarn org.slf4j.**
