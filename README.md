# Flox

Minimal Android TV client. Kotlin, plain Views, one WebView that resolves the stream and an ExoPlayer that plays it.

Source is VidLink (TMDB ids). The page is loaded in a hidden WebView only to resolve the stream: a document-start hook reports the HLS or DASH manifest its player fetches, plus the caption list from the source response, and the app plays that manifest in ExoPlayer with the page's Referer and Origin. Captions load as side-loaded subtitle tracks, off by default. Once the first frame renders the page is unloaded. If the native player errors, playback falls back to the page player for the rest of the session. Video is capped at 1080p, and boxes without a hardware HEVC decoder hide HEVC so an HEVC-only source falls back instead of stuttering. A failed or stalled load (no playback within 45 s) is reloaded once automatically; after that the failed screen is shown and CENTER retries. Long-press MENU reloads by hand.

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
| UP / DOWN | Show the native controls (seek bar, play). D-pad moves between buttons, BACK hides them |
| MENU | Cycle subtitles: off, then each track, device language first |
| UP / DOWN or long CENTER | Page player only: enter navigation mode over its buttons |
| MENU | Page player only: open its settings panel in navigation mode |
| long MENU | Reload player |
| In navigation mode: D-pad moves, CENTER selects, BACK closes the panel then exits | |
| BACK | Leave player |

Navigation mode uses the app's own focus logic (nearest button in the pressed direction, 2 px square ring), so it does not depend on the WebView's built-in spatial navigation.

Progress is written every 2 s from whichever player is active. In the page player, which autoplays muted without a user gesture, the app unmutes once playback is running. When an episode ends the app loads the next one from TMDB's episode list.

Boxes without a hardware HEVC decoder are reported to the page as HEVC-incapable so the player serves H.264. Every `<video>` gets a data-URI poster because the WebView's default poster fails CORS on crossorigin players and fires a spurious error.

## Ad blocking

Allowlist-based, no filter lists, no third-party engine.

1. Popup windows are refused.
2. Top-level navigation is restricted to the player's domains.
3. Scripts, documents and iframes from unknown hosts are dropped at the network layer. Media requests always pass.
4. A script injected at document start neutralises the popunder listeners, `window.open`, hidden form submits, the anti-adblock probe and overlay elements.

Blocked URLs are logged under the `FloxAdBlock` tag in debug builds.

## Requirements on the TV

Android 9 or newer with a System WebView of Chromium 89 or newer. Older WebViews cannot run the player's own bundle; the app shows a warning in that case.
