# Flox — Dark Style Reference
> Corporate monochrome for a 10-foot screen. Derived from DESIGN.md (Vercel light reference): same palette discipline, inverted value scale, TV-scaled type, zero border radius, no borrowed brand marks.

**Theme:** dark · **Radius:** 0px everywhere · **Chroma:** none, except Terminal Green as a rare accent

## Principles
- Depth through tonal steps (#171717 → #1f1f1f → #fafafa), never shadow, never blur.
- Hairlines are white at low alpha. Focus is a 2px solid ring. Nothing animates.
- Geist Sans reads, Geist Mono stamps. Labels and metadata are always mono, uppercase, tracked.
- Every corner is square. Cards, buttons, inputs, thumbnails, focus rings: 0px.
- No decorative imagery. Posters and stills are content and appear only where they carry information.

## Tokens — Colors

| Name | Value | Role |
|------|-------|------|
| Canvas | `#171717` | Page background |
| Card | `#1f1f1f` | Cards, inputs, elevated surfaces |
| Inverted | `#fafafa` | Filled primary button, inverted panel |
| Hairline | `rgba(255,255,255,0.08)` | 1px borders on cards |
| Hairline Strong | `rgba(255,255,255,0.16)` | Ghost button and input borders |
| Text Primary | `#fafafa` | Headings, titles, body |
| Text Secondary | `#c9c9c9` | Captions, eyebrows, overview text |
| Text Muted | `#8f8f8f` | Metadata, helper copy |
| Text Disabled | `#4d4d4d` | Disabled labels |
| Text On Inverted | `#171717` | Text inside filled buttons and inverted panels |
| Terminal Green | `#297a3a` | Focus ring on the filled button, success stamps. The only chromatic value |
| Pure Black | `#000000` | Player surface only |
| Focus Ring | `#fafafa` | 2px solid outline on any focused element |

## Tokens — Typography (TV scale)

Families: Geist Sans (400, 500) · Geist Mono (400, 500). Bundled.

| Role | Size | Family | Weight | Tracking | Case |
|------|------|--------|--------|----------|------|
| eyebrow | 14sp | Mono | 400 | +0.071em | UPPER |
| caption | 18sp | Sans | 400 | 0 | — |
| body | 22sp | Sans | 400 | 0 · lh 1.4 | — |
| heading | 36sp | Sans | 500 | -0.03em · lh 1.05 | — |
| display | 56sp | Sans | 500 | -0.05em · lh 1.0 | — |

Headlines never exceed weight 500. Body never below 18sp on TV.

## Tokens — Spacing & Shape
- Grid: 4dp. Scale: 4, 8, 12, 16, 24, 32, 48.
- Screen padding: 48dp horizontal (TV overscan safe).
- Radius: 0 for every element.
- Hairline: 1dp. Focus stroke: 2dp.
- Poster card: 160×240dp. Episode still: 224×126dp. Details poster: 280×420dp.

## Components

### Filled Button
Background Inverted `#fafafa`, text `#171717`, Geist Sans 500 18sp uppercase +0.04em, 48dp min height, 24dp horizontal padding, square. Focused: 2px Terminal Green ring. Used for PLAY / RESUME.

### Ghost Button
Transparent, 1px Hairline Strong border, text Text Secondary. Focused: 2px Focus Ring. Used for season pills.

### Poster Card
Card surface, 1px Hairline border, poster image edge-to-edge at top, below it: title (Sans 22sp Primary, 1 line) and one mono eyebrow line `YEAR · TYPE` (Secondary). Focused: 2px Focus Ring replaces the hairline. No scale, no glow.

### Row
Eyebrow label (mono, uppercase) 12dp above a horizontal list of Poster Cards with 16dp gaps. Rows separated by 32dp.

### Input
Card surface, 1px Hairline Strong, Geist Sans 22sp, square. Focused: 2px Focus Ring.

### State Stamp
Centered mono eyebrow in Text Muted: `LOADING`, `NO RESULTS`, `REQUEST FAILED`, `PLAYBACK FAILED`. No spinners, no skeletons.

### Player Hint
Bottom-right mono eyebrow on Pure Black, visible 2s: `PLAYER CONTROLS · BACK TO EXIT`.

## Screens
- **Home**: 48dp padding. Rows: CONTINUE WATCHING (if any), TRENDING MOVIES, TRENDING TV. Search is the first focusable element, top-left, as a ghost button.
- **Search**: Input at top, results grid of Poster Cards (6 per row at 1080p).
- **Details**: Flat canvas. Poster left (280×420). Right: eyebrow `YEAR · TYPE · RUNTIME`, heading title, body overview (max 5 lines), filled PLAY/RESUME. TV: season pills row, then episode list (still, `E01` eyebrow, name, runtime).
- **Player**: Pure Black, WebView edge-to-edge, no chrome.

## Do / Don't
- Do use tonal steps for hierarchy. Don't use shadow, blur, gradient.
- Do stamp metadata in mono uppercase. Don't set labels in Sans.
- Do use a 2px solid ring for focus. Don't scale, glow, or animate focus.
- Do keep corners square. Don't round anything.
- Don't introduce color. Don't use a third-party brand glyph.
