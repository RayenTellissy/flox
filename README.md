# Flox

Minimal Android TV client for VidFast. Kotlin, plain Views, one WebView.

## Build

Requires Android SDK (platform 34) and a JDK 17+. Copy `local.properties.example` to `local.properties` and fill in the values.

```
./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

## Sideload

```
adb connect <tv-ip>
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Remote

| Key | Action |
|-----|--------|
| CENTER / PLAY-PAUSE | Play or pause |
| LEFT / RIGHT | Seek 10 s |
| REWIND / FAST-FORWARD | Seek 30 s |
| MENU or long CENTER | Hand focus to the player's own controls (server, subtitles, quality) |
| BACK | Leave player focus, then leave player |

## Ad blocking

Allowlist-based, no filter lists, no third-party engine.

1. Popup windows are refused.
2. Top-level navigation is restricted to VidFast domains.
3. Scripts, documents and iframes from unknown hosts are dropped at the network layer. Media requests always pass.
4. A script injected at document start neutralises the popunder listeners, `window.open`, hidden form submits, the anti-adblock probe and overlay elements.

Blocked URLs are logged under the `FloxAdBlock` tag in debug builds.

## Requirements on the TV

Android 9 or newer with a System WebView of Chromium 89 or newer. Older WebViews cannot run VidFast's own bundle; the app shows a warning in that case.
