import type { InstrumentDefinition } from '../types'

const freq5 = [
  { id: 'f0', label: 'Never', value: 0 },
  { id: 'f1', label: 'Rarely', value: 1 },
  { id: 'f2', label: 'Sometimes', value: 2 },
  { id: 'f3', label: 'Often', value: 3 },
  { id: 'f4', label: 'Very often', value: 4 },
]

/**
 * Self-authored "focus & follow-through" self-check. Original wording throughout — it is
 * NOT the ASRS and does not reproduce the ASRS item text or its shaded-box decision rule.
 * Sum with our own descriptive splits; explicitly a within-person, non-diagnostic reflection.
 */
export const focusSelfCheck: InstrumentDefinition = {
  instrumentId: 'focus-selfcheck',
  instrumentVersion: '1.0.0',
  title: 'Focus & follow-through self-check',
  license: 'Self-authored (original items) — GPL-3.0, no third-party instrument reproduced',
  ledgerRef: 'INSTRUMENTS.md#focus-selfcheck',
  // Self-authored original (explicitly not the ASRS) — a Custom tool, not a validated instrument.
  provenance: { tier: 'custom' },
  nonDiagnostic: true,
  noScreeningFlag: true,
  estimatedMinutes: 3,
  items: [
    { id: 'intro', type: 'info', body: 'A private reflection on how focus and follow-through have felt lately. This tracks your own trend over time — it is not a test and not a screen.' },
    { id: 'q1', type: 'likert', prompt: 'I lost the thread of what I was doing partway through.', options: freq5, required: true },
    { id: 'q2', type: 'likert', prompt: 'I put off starting something that mattered, even when I meant to begin.', options: freq5, required: true },
    { id: 'q3', type: 'likert', prompt: 'Small details slipped past me that I would rather have caught.', options: freq5, required: true },
    { id: 'q4', type: 'likert', prompt: 'My attention drifted to something else while someone was talking to me.', options: freq5, required: true },
    { id: 'q5', type: 'likert', prompt: 'I felt restless, like it was hard to stay settled in one place.', options: freq5, required: true },
    { id: 'q6', type: 'likert', prompt: 'I jumped between tasks without finishing the first one.', options: freq5, required: true },
    { id: 'note', type: 'freeText', prompt: 'Context you want to remember (optional). Stays on your device.', excludeFromScoring: true },
  ],
  scoring: {
    scales: [
      {
        id: 'focus',
        method: 'sum',
        items: ['q1', 'q2', 'q3', 'q4', 'q5', 'q6'],
        bands: [
          { max: 9, label: 'Fewer focus bumps noted today', tone: 'neutral' },
          { min: 10, max: 16, label: 'A moderate amount noted today', tone: 'neutral' },
          { min: 17, label: 'More focus bumps noted today', tone: 'attention' },
        ],
        bandFraming:
          'These bands describe your own answers today against our own informal splits — not the source instrument’s cutoff. They are not a diagnosis, not a screen, and not a clinical threshold.',
      },
    ],
  },
  framing: {
    intro: 'This is a self-check, not a diagnosis. Everyone loses focus sometimes; this only reflects what you entered today and how it trends for you. Discuss any results with a clinician — the app makes no judgement about you.',
    crisisPosture: 'offline-static',
  },
}
