/*
 * The fixed 5-level mood scale, mirroring the app's model/Mood.kt, and a person's own names and
 * colours for it, read from their backup.
 *
 * The level 1–5 is the stable key; a name and a colour are how a level is shown. The phone lets a
 * person rename and recolour any of the five (ui/theme/MoodColors.kt, `MoodColors.withOverrides`
 * and `MoodLabels`), and its backup carries both, as `moodLabels` and `moodColors`. A view of a
 * person's own data shows their moods in their words and colours; the shipped scale below is what
 * every level falls back to, and what a view of anyone else's data draws.
 */

export type MoodLevel = 1 | 2 | 3 | 4 | 5

export interface MoodInfo {
  level: number
  label: string
  varName: string // CSS custom property for the colour
  washVar: string
}

export const MOODS: MoodInfo[] = [
  { level: 1, label: 'Awful', varName: '--mood-1', washVar: '--mood-1-wash' },
  { level: 2, label: 'Bad', varName: '--mood-2', washVar: '--mood-2-wash' },
  { level: 3, label: 'Meh', varName: '--mood-3', washVar: '--mood-3-wash' },
  { level: 4, label: 'Good', varName: '--mood-4', washVar: '--mood-4-wash' },
  { level: 5, label: 'Rad', varName: '--mood-5', washVar: '--mood-5-wash' },
]

export function moodFor(level: number): MoodInfo {
  return MOODS.find((m) => m.level === level) ?? MOODS[2]
}

/** Use a CSS custom property as a colour value (resolved at paint time, theme-aware). */
export function moodColor(level: number): string {
  return `var(${moodFor(level).varName})`
}

export function moodWash(level: number): string {
  return `var(${moodFor(level).washVar})`
}

/**
 * A mood as a word: the person's own name for the level where their backup carries one, the fixed
 * scale's otherwise. A blank or non-text name falls back, as `MoodLabels.forLevel` does on the
 * phone, so a hand-edited backup can neither blank a word nor put a number where one belongs.
 */
export function moodWord(level: number, labels: unknown): string {
  const supplied = typeof labels === 'object' && labels !== null ? (labels as Record<string, unknown>)[String(level)] : undefined
  return typeof supplied === 'string' && supplied.trim() ? supplied.trim() : moodFor(level).label
}

/* ═══════════════════════════════════════════════════════════════════════════
   A person's own mood colours (#280).

   A person's colour is their data, and it is held to what the ramp is held to
   (docs/COMPANION_DESIGN_SYSTEM.md §2.3.1) and one thing more: it fills a mood mark and
   nothing else, and a mood mark always sits beside its word, so the colour is never the only
   signal. Nothing in this file decides where a colour goes. What it does is make the one road
   in narrow:

   · A palette is OPAQUE. `OwnPalette` has no fields a caller can read, so the only way to get a
     colour out of it is `ownMoodFill`, and components/ownMoodColour.tree.test.ts holds every call
     of that to the value of a `style:--mood-fill` directive on a mood mark.
   · Every colour that comes out is `#rrggbb`, built from an integer by arithmetic. Nothing from
     the file reaches a style as text, so a crafted backup cannot write CSS.
   ═══════════════════════════════════════════════════════════════════════════ */

declare const OWN_PALETTE: unique symbol

/** A person's own colours for their moods. Opaque: read a colour out with {@link ownMoodFill}. */
export type OwnPalette = { readonly [OWN_PALETTE]: true }

const COLOURS = new WeakMap<OwnPalette, ReadonlyMap<number, string>>()

/**
 * One colour from a backup, as the phone writes it: `Color.toArgb()`, a signed 32-bit ARGB integer
 * (0xFFAE5747 arrives as -5351609). The alpha byte is dropped, because a mood mark is always drawn
 * solid: a see-through square would read as no square at all. Anything that is not an integer in
 * 32 bits is not a colour and gives null.
 */
function argbToHex(value: unknown): string | null {
  if (typeof value !== 'number' || !Number.isInteger(value)) return null
  if (value < -0x8000_0000 || value > 0xffff_ffff) return null
  return '#' + ((value >>> 0) & 0xff_ffff).toString(16).padStart(6, '0')
}

/**
 * The palette a backup's `moodColors` carries. Keys are the levels "1" to "5"; any other key, and
 * any value that is not a colour, is left out, and a level left out keeps the shipped ramp.
 * Anything that is not an object gives an empty palette: the whole shipped ramp.
 */
export function ownMoodColours(raw: unknown): OwnPalette {
  const byLevel = new Map<number, string>()
  if (typeof raw === 'object' && raw !== null && !Array.isArray(raw)) {
    for (const level of [1, 2, 3, 4, 5]) {
      const hex = argbToHex((raw as Record<string, unknown>)[String(level)])
      if (hex !== null) byLevel.set(level, hex)
    }
  }
  const palette = Object.freeze({}) as OwnPalette
  COLOURS.set(palette, byLevel)
  return palette
}

/**
 * The person's own colour for a level, or undefined where they chose none, so the mark keeps the
 * shipped ramp its stylesheet gives it. A value for the `--mood-fill` property of a mood mark and
 * for nothing else.
 */
export function ownMoodFill(level: number, palette: OwnPalette): string | undefined {
  return COLOURS.get(palette)?.get(level)
}
