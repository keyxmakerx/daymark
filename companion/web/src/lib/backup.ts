/*
 * TypeScript view of the Daymark app's BackupData JSON
 * (app/src/main/java/com/daymark/app/backup/BackupManager.kt).
 *
 * This is the single, versioned data contract the Phase-0 viewer reads. It is a
 * faithful, defensive mirror: every field that the app defaults is optional here,
 * so older backups still parse. We never mutate or re-upload — this file is read
 * entirely in the browser and never leaves the device.
 */

export interface BackupEntry {
  id: number
  dateTime: number // epoch millis
  moodLevel: number // 1..5
  note: string
  photoPath?: string | null
}

export interface BackupActivity {
  id: number
  name: string
  iconKey: string
  sortOrder: number
  archived: boolean
}

export interface BackupRef {
  entryId: number
  activityId: number
}

export interface BackupJournal {
  id: number
  dateTime: number
  title: string
  body: string
}

export interface BackupGoal {
  id: number
  title: string
  activityId: number | null
  targetPerWeek: number
  createdAt: number
  archived: boolean
  cue?: string
  routine?: string
}

export interface BackupSleepLog {
  id: number
  night: number
  bedTime: number
  wakeTime: number
  sleepLatencyMin: number
  awakeMin: number
  quality: number
  note: string
}

export interface BackupAssessment {
  id: number
  key: string
  dateTime: number
  score: number
  bandLabel: string
}

export interface BackupData {
  version: number
  exportedAt: number
  entries: BackupEntry[]
  activities: BackupActivity[]
  refs: BackupRef[]
  journal?: BackupJournal[]
  goals?: BackupGoal[]
  sleepLogs?: BackupSleepLog[]
  assessments?: BackupAssessment[]
  moodLabels?: Record<string, string>
  moodColors?: Record<string, number>
  // Remaining tables (treatments, trackers, trackerLogs, reminders, photos,
  // achievements, thoughtRecords) exist in the file but are not rendered yet in
  // this Phase-0 scaffold; they are intentionally not typed here to keep the
  // surface small. They are preserved untouched — we only read.
}

/** The largest backup we will read into memory (defensive; backups are small JSON). */
export const MAX_BYTES = 64 * 1024 * 1024 // 64 MiB

export class BackupParseError extends Error {}

/**
 * Parse + minimally validate a backup JSON string. Throws BackupParseError with a
 * human-readable message on anything that is not a recognisable Daymark backup.
 */
export function parseBackup(text: string): BackupData {
  let raw: unknown
  try {
    raw = JSON.parse(text)
  } catch {
    throw new BackupParseError('That file is not valid JSON. Pick a Daymark backup (a .json file from Settings → Export backup).')
  }
  if (typeof raw !== 'object' || raw === null) {
    throw new BackupParseError('That JSON is not a Daymark backup (expected an object at the top level).')
  }
  const obj = raw as Record<string, unknown>
  if (!Array.isArray(obj.entries) || !Array.isArray(obj.activities)) {
    throw new BackupParseError('This does not look like a Daymark backup — it is missing the "entries"/"activities" arrays.')
  }
  const version = typeof obj.version === 'number' ? obj.version : 0
  const exportedAt = typeof obj.exportedAt === 'number' ? obj.exportedAt : 0

  return {
    version,
    exportedAt,
    entries: (obj.entries as BackupEntry[]).map(coerceEntry),
    activities: obj.activities as BackupActivity[],
    refs: Array.isArray(obj.refs) ? (obj.refs as BackupRef[]) : [],
    journal: Array.isArray(obj.journal) ? (obj.journal as BackupJournal[]) : [],
    goals: Array.isArray(obj.goals) ? (obj.goals as BackupGoal[]) : [],
    sleepLogs: Array.isArray(obj.sleepLogs) ? (obj.sleepLogs as BackupSleepLog[]) : [],
    assessments: Array.isArray(obj.assessments) ? (obj.assessments as BackupAssessment[]) : [],
    moodLabels: (obj.moodLabels as Record<string, string>) ?? {},
    moodColors: (obj.moodColors as Record<string, number>) ?? {},
  }
}

function coerceEntry(e: BackupEntry): BackupEntry {
  return {
    id: e.id,
    dateTime: e.dateTime,
    moodLevel: Math.min(5, Math.max(1, Math.round(e.moodLevel))),
    note: typeof e.note === 'string' ? e.note : '',
    photoPath: e.photoPath ?? null,
  }
}
