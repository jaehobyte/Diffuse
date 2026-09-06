# DESIGN.md — AI Image Editor (Android)

> Pinterest's visual language (red accent, warm-cream neutrals, image-first) adapted for a mobile image editor.
> This is a **decision list**, not a mood board.
> Source of truth for token values is `core/ui/theme/Tokens.kt`. If this file and the code disagree, follow the code and fix this file.

---

## 1. Visual Theme & Atmosphere

- The photo is the hero. Chrome is achromatic with a faint warm cast so it never competes with image color.
- The app has two modes:
  - **Browse mode** (home, gallery, templates, export result): light cream chrome, straight from Pinterest.
  - **Edit mode** (canvas screen): warm dark chrome. Bright surroundings distort exposure judgment, so everything around the canvas is dark.
- The accent (Pinterest Red) appears **at most once per surface at rest**, never decoratively. A
  surface is the top bar, the tool strip, or the frontmost sheet. Browse has one surface and so still
  gets exactly one accent.
- A **transient active state** may add one more accent, and only while it lasts: the progress
  spinner during AI work, the mic while listening. Nothing that persists after the user stops
  interacting counts as transient.
  *(Ruling, T25. §1 previously said "one place per screen", which §4 already contradicted twice: Edit
  mode requires an accent Export pill in the top bar and an accent indicator in the tool strip, and
  the loading overlay's spinner is accent on top of both. The intent — the accent marks the one thing
  to press, and the photo stays the hero — is unchanged; the unit is now the surface, and momentary
  states are named as the exception they always were.)*
- Density: generous. Minimum 12dp between controls, 16dp horizontal screen padding.
- Tone: friendly but tool-like. No emoji, no speech-bubble tips, no mascot.

## 2. Color Palette & Roles

### Brand
| Token | Hex | Role |
|---|---|---|
| `accent` | `#E60023` | Primary button, active tab indicator, selected tool icon |
| `accentPressed` | `#CC001F` | Primary button pressed |
| `onAccent` | `#FFFFFF` | Text/icon on accent |

### Browse mode (Light)
| Token | Hex | Role |
|---|---|---|
| `canvasLight` | `#FFFFFF` | Top bar, modals, sheets |
| `surfaceSoft` | `#FBFBF9` | Page background |
| `surfaceCard` | `#F6F6F3` | Image tile background, search bar |
| `surfaceSecondary` | `#E5E5E0` | Secondary button fill |
| `hairline` | `#DADAD3` | 1dp dividers |
| `ink` | `#000000` | Body, headings |
| `inkSecondary` | `#5F5F5A` | Secondary text, metadata |

### Edit mode (Dark)
| Token | Hex | Role |
|---|---|---|
| `editBackground` | `#1C1C19` | Area outside the canvas |
| `editSurface` | `#262622` | Tool strip, bottom sheet |
| `editSurfaceRaised` | `#33332E` | Cards inside sheets, slider track |
| `editHairline` | `#3F3F39` | Dark-mode dividers |
| `editInk` | `#F6F6F3` | Dark-mode body text |
| `editInkSecondary` | `#A3A39B` | Dark-mode secondary text |
| `canvasCheckerA` / `canvasCheckerB` | `#2E2E2A` / `#262622` | Transparency checkerboard, 8dp cells |

### Semantic
| Token | Hex | Role |
|---|---|---|
| `success` | `#1E7A46` | Export complete |
| `warning` | `#B8741A` | Low AI credits, offline |
| `error` | `#E60023` | Failure. Same value as accent, but keep the token separate. |

Rules
- If accent and error share a screen, render the error as text only — never as a filled button.
- No gradients. Brand red is flat only.

## 3. Typography

- Font: **Pretendard** (Korean) with Inter fallback. Pin Sans is proprietary; do not use it.
- The editor rarely has screen titles. `headingXl` is for onboarding and empty states only.

| Token | Size / Weight / Line-height | Use |
|---|---|---|
| `headingXl` | 28sp / 700 / 1.2 / -0.5 tracking | Onboarding, empty gallery |
| `headingLg` | 22sp / 600 / 1.25 | Sheet title (e.g. "배경 제거") |
| `headingMd` | 18sp / 600 / 1.3 | Section title, template card title |
| `bodyMd` | 16sp / 400 / 1.4 | Default body, sheet description |
| `bodyStrong` | 16sp / 600 / 1.4 | Button label |
| `bodySm` | 14sp / 400 / 1.4 | Metadata, helper text |
| `label` | 12sp / 500 / 1.3 / +0.2 tracking | Tool icon label, slider value |
| `mono` | 13sp / 500 JetBrains Mono | Pixel coordinates, file size, HEX values |

## 4. Component Stylings

### Buttons
- All buttons are **pills** (`radius = 16dp`, height 40dp). Large CTAs are 48dp tall.
- `primary`: `accent` fill, `onAccent` text. One per screen.
- `secondary`: `surfaceSecondary` fill (`editSurfaceRaised` in dark), ink text.
- `tertiary`: transparent, ink text. Cancel / close.
- `iconCircle`: 40dp circle for overlays on photos. Fill `#FFFFFF` at 88% or `#000000` at 60%.
- Disabled: 38% alpha. Do not change the color.

### Tool strip (bottom of Edit mode)
- Height 72dp, horizontally scrollable. Items 64dp wide: 24dp icon + `label` text.
- Selected tool: icon and label in `accent`, 2dp indicator underneath. No background fill.
- AI tools carry a 6dp `accent` dot at the top-right of the icon. No "AI" badge text.

### Prompt bar (Edit mode, inside a sheet)
- Height 48dp, radius 16dp (the pill radius), fill `editSurfaceRaised`. Full sheet width.
- Leading 24dp mic icon, trailing 24dp send icon, both with 48dp hit areas. Text `bodyMd` in
  `editInk`; placeholder `editInkSecondary`. Horizontal padding 12dp, 8dp between icon and text.
- Both icons are `editInk` at rest. **The sheet's one accent stays on its Apply pill** — a prompt bar
  never takes it, so every sheet commits the same way. The send icon earns its weight from the
  enabled/disabled state and the IME Done key instead.
- The mic turns `accent` while listening, as the transient exception in §1 — **a fill change only**:
  no glow, no pulse, no animated ring.
- While listening, send is replaced by a stop icon in the same slot.
- Disabled: 38% alpha, color unchanged.
- No mic at all when the device has no speech recognizer. The bar is never the only way to act — it
  sits alongside the sheet's buttons, never replacing them.

### Bottom sheet (detail adjustments)
- Top corners 24dp. Drag handle 32×4dp in `editHairline`.
- Max height 45% of screen. The canvas is always at least half visible.
- Layout inside: title (`headingLg`) → controls → pinned bottom row [Cancel | Apply]. Apply is `primary`.

### Slider
- Track 4dp, thumb 20dp white circle with 1dp `editHairline` stroke.
- Value label is **pinned to the right** in `mono`, not floating above the thumb. Zero-centered adjustments (exposure, temperature) get a 2dp tick at the track center.
- Double-tap resets to default.

### Image tile (Browse)
- Corner radius 16dp. Background `surfaceCard`; while loading, show flat `surfaceCard` — no skeleton shimmer.
- No text over the tile. Metadata goes below in `bodySm`.
- Long-press reveals `iconCircle` actions (delete, duplicate).

### Top bar
- Browse: white, 56dp, no title. Avatar left, search right.
- Edit: `editSurface`, 56dp. Left [←], center [Undo][Redo], right [Compare (hold to show original)][Export as `primary` pill].

### State display
- Loading: `#000000` 40% overlay on the canvas + centered circular progress in `accent` + one line of text (e.g. "배경을 분리하는 중").
- Error: bottom snackbar on a dark surface. No toasts.
- Confirmation dialogs **only for destructive actions** (delete, leave without saving).

## 5. Layout Principles

- Spacing scale: 4 / 8 / 12 / 16 / 24 / 32 dp. No other values.
- Browse grid: 2-column masonry, 8dp column gap, 16dp side padding. Tablets (600dp+): 3 columns.
- Edit screen structure (top → bottom): top bar 56 / canvas (all remaining space) / tool strip 72. Sheets rise above the tool strip.
- Canvas margin: at least 16dp of `editBackground` around the photo.
- Touch targets: minimum 48×48dp. Even 24dp icons get a 48dp hit area.

## 6. Depth & Elevation

- **One shadow level only**: `0 2dp 8dp #000000 12%`. Used on floating sheets and `iconCircle`.
- All other hierarchy is expressed through surface color steps (`surfaceSoft` → `canvasLight` → card).
- In dark mode, replace shadows with a 1dp `editHairline` border.

## 7. Do's and Don'ts

Do
- One accent placement per screen
- Controls over photos are translucent `iconCircle`s
- Every button is a pill
- Always show progress and a cancel button during AI work
- "Hold to compare with original" is the single comparison gesture

Don't
- Gradients, neon, glow
- More than one shadow level
- Text overlaid on tiles
- Sheets covering more than half the canvas
- Mixed icon sets (Material Symbols Rounded only)
- Marketing copy like "✨ AI Magic". Feature names are verbs: "배경 제거", "채우기", "확대"

## 8. Responsive & Gesture

- Portrait by default. In landscape only the canvas rotates; the tool strip moves to the right edge as a vertical strip.
- 600dp+: Browse uses 3 columns; Edit sheets become a right-side panel (320dp).
- Canvas gestures: pinch to zoom, two-finger pan, double-tap to fit. One finger belongs to the current tool (brush / mask).
- System bars: in Edit mode the status bar matches `editSurface`; navigation bar is transparent, edge-to-edge.

## 9. Agent Prompt Guide

Quick reference
```
accent #E60023 · light bg #FBFBF9 · dark bg #1C1C19 · dark surface #262622
radius pill 16dp · sheet 24dp · button 40dp · icon 24dp · touch 48dp
font Pretendard · label 12sp · body 16sp
```

Example prompts
- "Build the bottom tool strip Composable per section 4 of DESIGN.md. Selected state is the accent indicator only."
- "Build the background-removal bottom sheet. Max height 45%, [Cancel | Apply] pinned at the bottom, Apply is a primary pill."
- "Build the Browse home as a 2-column masonry grid. No text on tiles; metadata below in bodySm."

Agent rules
- Read this file before any UI work. Reference color and spacing values only through `Tokens.kt`.
- When a decision is missing here, pick the simplest Pinterest-style answer and note it in the PR description.
- User-facing strings are Korean and live in `strings.xml`. Do not hardcode them in Composables.
