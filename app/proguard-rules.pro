-keepclassmembers class com.flox.tv.player.PlayerBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# TDLib talks to its Java classes over JNI
-keep class org.drinkless.tdlib.** { *; }

# The FFmpeg decoder's JNI reaches these buffers by name
-keep class androidx.media3.decoder.SimpleDecoderOutputBuffer { *; }
