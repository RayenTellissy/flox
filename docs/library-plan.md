# Flox Library: pre-download to Telegram, play natively on TV

## Goal

Pick episodes on the Mac, they end up in a private Telegram channel, flox on the
TV plays them through the existing native player. No file stays on the laptop
beyond a one-episode temp buffer. No paid services.

## Constraints and facts

- Telegram free tier: unlimited total storage, 2 GB per file, documents stored
  byte-for-byte.
- Uploads must go through a user account over MTProto (TDLib). The Bot API caps
  uploads at 50 MB.
- Telegram exposes no direct HTTPS links, so the TV needs TDLib too.
- Source is VidLink, ripped from the HLS manifest the flox sniffing script
  already finds. Quality is whatever VidLink serves, usually 1080p.

## Channel schema

- Private channel `Flox Library`, auto-created by the Mac app, overridable in
  settings on both apps.
- One message per file, sent as document. Caption is JSON:

  ```json
  {"tmdb": 1399, "type": "tv", "s": 1, "e": 3, "quality": "1080p", "part": 1, "parts": 2}
  ```

  Movies omit `s` and `e`. Files over 2 GB are split into numbered parts.
- English subtitles uploaded as `.srt` in a reply to part 1.

## Mac app (`flox-mac`, separate repo)

SwiftUI + TDLib + bundled ffmpeg. Local Xcode build, no notarization.

- Login with phone number and code, main account.
- TMDB search, show/movie, season, multi-select episodes. Same TMDB key as flox.
- Hidden WKWebView loads the VidLink embed with the sniffing JS copied from
  flox `PlayerBridge`. ffmpeg pulls the highest rendition to `.mp4` and grabs
  English captions.
- Queue is sequential: rip, upload, delete temp. Power assertion held while
  running. One automatic retry, then marked failed. Notification when done.
- Library view reads the channel on launch: uploaded episodes greyed out,
  delete supported.

## TV app (flox)

- TDLib bundled in-app. QR login. Finds the channel by name and indexes captions
  into an in-memory map keyed by TMDB id, season, episode.
- Episode screen: badge on uploaded episodes, Play prefers the Telegram file.
  Home gets a `Library` row listing uploaded shows.
- Playback goes through the existing `NativePlayer`. A TDLib-backed
  `DataSource` stitches parts by byte offset and seeks by requesting TDLib
  downloads at offsets. VidLink path is the untouched fallback.
- Continue-watching store is shared with VidLink playback. Autoplay next
  episode when it exists in the channel.
- TDLib file cache capped around 6 GB, LRU eviction. Nothing is auto-deleted
  from Telegram.
- ABI: verify the box over adb first. Ship arm64 only if it is 64-bit,
  otherwise arm64 + armv7.

## Order of work

1. Spike A: TDLib streams a channel document into ExoPlayer on the box, with
   seek.
2. Spike B: headless VidLink rip on the Mac produces a playable `.mp4`.
3. Mac app.
4. TV integration.

Either spike failing changes the plan before any real code is written.

## Paste-links mode (Mac app)

- Second way to fill a batch: pick show and season, open a paste panel with one
  field per episode, labels prefilled from a start episode number and editable,
  add or remove fields freely. Movies get a single field.
- Links must be direct file URLs. If the response is not a video content type
  the URL is handed to bundled yt-dlp as a fallback. No magnets.
- Files are uploaded as-is, no remux or transcode. ffprobe fills `quality` and
  `codec` in the caption; the TV warns when the codec is unsupported.
- Embedded subtitles pass through. No external subtitle field in v1.
- Hosts that need cookies or a referer fail with a clear error in v1.
- A batch is one mode only. The queue holds batches of either kind.

## Custom player UI (TV app)

Built first, independent of Telegram.

- ExoPlayer stays the engine. `PlayerView` controller disabled, replaced by an
  overlay owned by flox that wraps `NativePlayer` and covers both sources. The
  WebView fallback keeps VidLink's own UI.
- Controls: play/pause, seek bar with position and duration, back, subtitles
  toggle (only when a track exists), next episode (only when one exists).
- D-pad: CENTER toggles play/pause, LEFT/RIGHT seek 10 s and accelerate when
  held, UP/DOWN reveal the bar and move focus, BACK hides the bar then exits.
  Overlay auto-hides after 4 s. Seek amount shown as a small label.
- Icons follow the existing set: Higgsfield GPT Image 2, flat white geometry,
  keyed to transparent PNG. New: pause, rewind, forward, subtitles, next.
  Rewind/forward are plain double triangles. Player size is 48 dp, exported
  at xhdpi and xxhdpi alongside the existing 24 dp set.
