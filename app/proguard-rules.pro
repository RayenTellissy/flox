-keepclassmembers class com.flox.tv.player.PlayerBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# TDLib talks to its Java classes over JNI
-keep class org.drinkless.tdlib.** { *; }
