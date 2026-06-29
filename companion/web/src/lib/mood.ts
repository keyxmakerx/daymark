/* The fixed 5-level mood scale, mirroring the app's model/Mood.kt. */

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
