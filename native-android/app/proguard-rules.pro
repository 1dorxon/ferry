# libwebrtc calls back into these classes from native code by name.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
