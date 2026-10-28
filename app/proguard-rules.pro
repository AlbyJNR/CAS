# The trailer box talks back to CAS through a JavaScript interface; R8 must keep those method names.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
