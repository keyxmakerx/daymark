# Design system — "Modern paper"

Daymark's visual language is warm, flat, and paper-like: stationery surfaces, hairline rules
instead of heavy shadows, a serif "journal" voice for headings, and muted, earthy mood colors.
All icons are **original** hand-drawn vector drawables created for Daymark (no third-party packs,
no emoji) and are licensed GPL-3.0 with the project.

## Color tokens (`ui/theme/Color.kt`)

**Light:** paper `#F4EFE6` · sheet/surface `#FCFAF5` · ink `#2A2722` · soft `#6B655B` ·
faint `#A49C8E` · hairline `#E7DFD1` · accent (ink) `#33302A`.
**Dark ("night paper"):** bg `#1B1A17` · surface `#24221D` · ink `#EBE5D8` · lines `#34312A` ·
accent inverts to `#EBE5D8` (text-on-accent `#1B1A17`).

**Mood scale (awful → rad):** `#AE5747` · `#C27C46` · `#C6A24E` · `#8FA268` · `#5E8A66`, each
with a lighter "wash" variant for calendar tints. Mood colors live in a custom `MoodColors`
holder (`LocalMoodColors`) because they sit outside the standard Material 3 roles, and they are
**never** recolored by dynamic color.

These tokens are mapped onto the M3 `ColorScheme` in `Theme.kt`; `surfaceTint` is transparent and
tonal-elevation steps are avoided to keep surfaces flat. **Dynamic color defaults to off** so the
paper identity always wins.

## Typography (`ui/theme/Type.kt`)

A **serif** for display/headlines/titleLarge and the italic **diary-note** style (journal feel),
and a clean **sans** for body, labels, and numbers. (Bundling specific typefaces — e.g. Fraunces +
Inter — is a planned polish step; the app currently uses the platform serif/sans families.)

## Shape, spacing, elevation

Restrained radii (`Shape.kt`: 8/12/15/16/22dp). The signature container is `PaperSurface` — a flat
surface with a 1dp hairline border and at most a whisper of shadow (no shadow in dark mode). A
spacing scale lives in `Spacing.kt`.

## Components (`ui/components/`)

`MoodFaceIcon` (Canvas-drawn face, outline vs. filled-when-selected), `PaperSurface`, the
day-grouped timeline + `EntryRow`, activity chips, `StatCard`, calendar cells, `YearInPixelsGrid`,
and settings rows. The mood picker uses an enlarged tap target (whole face+label chip).

## Icons (`res/drawable/ic_*`, `ui/icon/`)

Original 24×24 stroke-style vector drawables: 5 mood faces, ~17 activities, and UI/nav glyphs.
Tinted at the Compose layer from `colorScheme`/`moodColors`, so one asset serves light & dark.

## Motion

Motion follows what a move means (`ui/DaymarkAppScaffold.kt`), so there are three transitions,
not one blanket slide:

- **Between the five tabs** — siblings, not a hierarchy — a non-directional **fade-through**: the
  outgoing screen fades out (~110ms), then the incoming one fades in while scaling up from 92%
  (~220ms after a ~90ms gap).
- **Drilling into a list or detail screen** (History, Settings, Trackers, the Sky, …) — a
  **shared-axis Z** move: the new screen scales up from 85% on a spring with a short fade (150ms),
  and scales back down the same way on back.
- **Creating or editing something** (an entry, a journal page, a goal, a check-in, a sleep log, a
  thought record, a year review) and the in-the-moment support flows — a **sheet rising** from the
  bottom: the screen slides up a third of its height on a spring and fades in; dismissing it slides
  and fades it back down.

The spatial moves use springs (damping 0.9, medium-low stiffness) rather than fixed tweens so a
transition interrupted mid-way reverses smoothly instead of snapping.
