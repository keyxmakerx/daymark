# Design system: "Modern paper"

Daymark's visual language is warm, flat and paper-like: stationery surfaces, hairline rules instead
of heavy shadows, a serif "journal" voice for headings, and muted, earthy mood colours. Every icon
and drawing is **original**, made for Daymark (no third-party packs, no emoji), and licensed GPL-3.0
with the project. The web consoles' sibling system is
[COMPANION_DESIGN_SYSTEM.md](COMPANION_DESIGN_SYSTEM.md); the words the app may use are governed by
[FEATURES.md](FEATURES.md) §0.

## Colour tokens (`ui/theme/Color.kt`)

**Light:** paper `#F4EFE6` · sheet/surface `#FCFAF5` · ink `#2A2722` · soft `#6B655B` ·
faint `#A49C8E` · hairline `#E7DFD1` · accent (ink) `#33302A` · alarm (clay) `#9A5044` on `#F3E0DB`.
**Dark ("night paper"):** bg `#1B1A17` · surface `#24221D` · ink `#EBE5D8` · soft `#B7AF9E` ·
faint `#7C7568` · lines `#34312A` · the accent inverts to `#EBE5D8` (text on it `#1B1A17`) · alarm
`#CB8473` on `#3A2C28`.

**Mood scale (Awful → Rad):** `#AE5747` · `#C27C46` · `#C6A24E` · `#8FA268` · `#5E8A66`, each with
a lighter "wash" in light mode and a darker one in dark mode.

- Mood colours live in a `MoodColors` holder (`LocalMoodColors`, read as
  `MaterialTheme.moodColors.forLevel(1..5)`) and mood names in `LocalMoodLabels`, because they sit
  outside the Material 3 roles. That is how a person's own names and colours carry everywhere.
  **Always read them from there; never hard-code a mood colour in UI.**
- The level 1–5 is the stable key in the database. Names and colours are presentation.
- A mood colour is data, the value the person logged. It is never a status, success or warning
  colour, and wallpaper colours never recolour it.
- A mood colour is only ever one entry's own. Nothing blends two mood colours or colours a day by
  its average: Insights → Month draws one dot per entry on plain paper, with the day number in ink
  (`ui/calendar/CalendarDays.kt`, `MonthGridSourceTest`). The Week bars and Home's week strip still
  colour a day by its average: not built, #411.

`ui/theme/Theme.kt` maps the tokens onto the Material 3 `ColorScheme`. `surfaceTint` is transparent
and tonal elevation is avoided, to keep surfaces flat.

**The alarm is its own colour.** Errors, refusals and destructive actions (a Delete label, the lock
screen's error, the crisis button) use the clay tokens in `ui/theme/Color.kt`, which are the web
consoles' `--clay` and `--clay-wash`, value for value
([COMPANION_DESIGN_SYSTEM.md](COMPANION_DESIGN_SYSTEM.md) §2.3.4). `ui/theme/Theme.kt` maps them onto
`error` and `errorContainer`. No colour-scheme role is ever a mood colour, so recolouring a mood never
moves the alarm; `ColorSchemeSourceTest` holds the scheme to that and to the web's values. The dark
wash shares its value with the dark Awful wash and is still a token of its own. With dynamic colour
on, the system supplies every role, the error roles included (#309).

**The faint ink never carries words.** Faint (`tertiary`) measures 2.61:1 on the sheet and is for
decoration only: rules and empty marks. A word that steps back takes the soft ink
(`onSurfaceVariant`), which clears 4.5:1 on the sheet, the paper and a menu in both themes.
`FaintInkSourceTest` fails when the faint ink reaches words. Two small labels on the hairline fill, the
provenance badge and the swipe row's "Keep swiping", measure 4.36:1 in the light theme: not built,
#408.

**Dynamic colour** is a Settings switch on Android 12 and later. The stored setting defaults to on
(`data/SettingsRepository.kt`), so a fresh install on those phones takes its colours from the
wallpaper, although `DaymarkTheme`'s own default and its comment say the paper palette should win.
Mood colours are unaffected either way.

## Night palettes

Two surfaces are always dark, whatever the theme.

- **The Sky** (`sky/SkyPalette.kt`): ground `#07070A`, ink `#EBE5D8`, faint `#8E887A`. The ground is
  near-black and never pure black, so a glow has something to fade into and an OLED screen does not
  switch its pixels off under a halo. Ink is the Sky's ceiling: its chrome, decorative field and
  selection ring. Faint is for labels, leader lines and the project thread, never a star. It measures
  5.70:1 on the ground, so it is not faint; chrome is told apart from the person's stars by stroke,
  size and form. Mood marks on the Sky's list and detail sheet are equalised to 6.5:1 against the
  ground, and 4.5:1 is a hard floor. The full rules are in [SKY.md](SKY.md).
- **The year view and Review my year** (`ui/components/YearInStarsGrid.kt`): ground `#16150F`, ink
  `#EBE5D8`, faint `#8E887A`. The two grounds differ, and are left apart on purpose while the year
  view still sizes stars by mood ([FEATURES.md](FEATURES.md) §3, #148).

## Typography (`ui/theme/Type.kt`)

A **serif** for display, headlines, `titleLarge` and the italic diary-note style, and a clean
**sans** for smaller titles, body, labels and numbers. The serif is Fraunces, bundled in the app and
never fetched; all other text uses the platform sans, for good, and there is no Inter (#251). Not
built: bundling Fraunces, so the serif roles use the platform serif and `res/font` does not exist:
#363.

## Shape, spacing, elevation

Restrained radii (`ui/theme/Shape.kt`): 8, 12, 15, 16 and 22 dp. The signature container is
`PaperSurface`: a flat surface with a 1 dp hairline border. Paper surfaces carry at most a whisper
of shadow, and none in dark mode. The spacing scale is in `ui/theme/Spacing.kt`; screens are padded
16 dp.

## Navigation bar

The bottom bar holds Insights, Journal, Home, Goals and More, with Home raised into a circular button
in the centre (`ui/components/RaisedCenterNavBar.kt`). Every item is a tab to a screen reader ("Home,
tab, selected"). The selected cue is never colour alone: a flat item gets a short bar under its icon,
and the disc is filled with the accent only while Home is the current tab.

## Components (`ui/components/`)

`MoodFaceIcon` (drawn on a Canvas; outlined, or filled when selected), `PaperSurface`, `EntryRow`
and the day-grouped timeline, `ActivityChip`, `EntryPhoto`, `SwipeToDeleteRow`,
`ConsistencyHeatmap`, `YearInPixelsGrid`, `YearInStarsGrid`, `PoseFigure` and `ProvenanceBadge`.
The mood picker's tap target is the whole face and label.

## Icons and drawings

Stroke-style 24×24 vector drawables in `res/drawable`: 17 activity icons, looked up by key in
`ui/icon/ActivityIcons.kt`, and 7 interface glyphs. The mood faces, the movement pose figures and
the stars are drawn in code. Everything is tinted at the Compose layer from `colorScheme` or
`moodColors`, so one asset serves light and dark.

## Motion

Motion follows what a move means (`ui/DaymarkAppScaffold.kt`), so there are three transitions, not
one blanket slide:

- **Between the five tabs**, which are siblings rather than a hierarchy: a non-directional
  **fade-through**. The outgoing screen fades out (about 110 ms), then the incoming one fades in
  while scaling up from 92% (about 220 ms, after a 90 ms gap).
- **Drilling into a list or detail screen** (All entries, Settings, Trackers, the Sky, …): a
  **shared-axis Z** move. The new screen scales up from 85% on a spring with a short fade (150 ms),
  and scales back down the same way on back.
- **Creating or editing something** (an entry, a journal page, a goal, a check-in, a sleep log, a
  thought record, the year review) and the in-the-moment support screens: a **sheet rising** from
  the bottom. The screen slides up a third of its height on a spring and fades in; dismissing it
  slides and fades it back down.

The spatial moves use springs (damping 0.9, medium-low stiffness) rather than fixed tweens, so a
transition interrupted halfway reverses smoothly instead of snapping. The breathing pacer ignores
the system animation scale on purpose: its timing is what a person breathes along with.
