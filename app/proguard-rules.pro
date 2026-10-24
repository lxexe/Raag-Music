# NewPipe's signature decipherer executes player JavaScript through Rhino.
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter { *; }
-dontwarn org.mozilla.javascript.tools.**
# NewPipe's JavaScript helper explicitly selects Rhino's interpreted mode and
# safe standard objects. Its unused desktop JavaBeans, JSR-223 and optimizer
# integrations reference APIs absent on Android. Match NewPipe Android's rules.
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# Protobuf Lite reflects generated message field names when reading playlist continuations.
# Follow protobuf's consumer keep rule: https://github.com/protocolbuffers/protobuf/blob/main/java/lite/proguard.pgcfg
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
