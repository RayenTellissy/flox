# Flox

Minimal Android TV client for embedded movie players. Kotlin, plain Views, one WebView.

Player is VidLink (TMDB ids). Source lists are capped at 1080p so low-end boxes never get handed a 4K stream. A failed or stalled load (no playback within 45 s) is reloaded once automatically; after that the failed screen is shown and CENTER retries. Long-press MENU reloads the player by hand.

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
| UP / DOWN or long CENTER | Enter navigation mode over the player's own buttons |
| MENU | Open the player's settings panel in navigation mode |
| long MENU | Reload player |
| In navigation mode: D-pad moves, CENTER selects, BACK closes the panel then exits | |
| BACK | Leave player |

Navigation mode uses the app's own focus logic (nearest button in the pressed direction, 2 px square ring), so it does not depend on the WebView's built-in spatial navigation.

Progress is read straight from the page's `<video>` element every 2 s. The player autoplays muted without a user gesture, so the app unmutes it once playback is running. When an episode ends the app loads the next one from TMDB's episode list.

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
