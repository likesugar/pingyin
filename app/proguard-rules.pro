-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.pinyin.typing.** { *; }
-dontwarn org.json.**
