import type { InstrumentDefinition } from '../types'

const likert5 = [
  { id: 'a0', label: 'Not at all', value: 0 },
  { id: 'a1', label: 'A little', value: 1 },
  { id: 'a2', label: 'Some of the time', value: 2 },
  { id: 'a3', label: 'Most of the time', value: 3 },
  { id: 'a4', label: 'All of the time', value: 4 },
]

/**
 * Self-authored daily wellbeing self-check. Entirely original wording; not derived from
 * WHO-5 or any licensed scale. Descriptive bands only — a mirror of the person's own
 * answers today, never a screen or diagnosis.
 */
export const wellbeingCheck: InstrumentDefinition = {
  instrumentId: 'wellbeing-selfcheck',
  instrumentVersion: '1.0.0',
  title: 'Daily wellbeing self-check',
  license: 'Self-authored (original items) — GPL-3.0, no third-party instrument reproduced',
  ledgerRef: 'INSTRUMENTS.md#wellbeing-selfcheck',
  nonDiagnostic: true,
  noScreeningFlag: true,
  estimatedMinutes: 2,
  items: [
    { id: 'intro', type: 'info', body: 'A quick, private check-in on how the last little while has felt for you. There are no right answers — just your own read on today.' },
    { id: 'w1', type: 'likert', prompt: 'Over the last while, I have felt calm and settled.', options: likert5, required: true },
    { id: 'w2', type: 'likert', prompt: 'Over the last while, I have felt rested and had energy to spare.', options: likert5, required: true },
    { id: 'w3', type: 'likert', prompt: 'Over the last while, things I did felt worth doing.', options: likert5, required: true },
    { id: 'w4', type: 'likert', prompt: 'Over the last while, I have felt hopeful about the days ahead.', options: likert5, required: true },
    { id: 'w5', type: 'likert', prompt: 'Over the last while, I have felt close to people who matter to me.', options: likert5, required: true },
    { id: 'note', type: 'freeText', prompt: 'Anything you want to note (optional). Stays on your device.', excludeFromScoring: true },
  ],
  scoring: {
    scales: [
      {
        id: 'wellbeing',
        method: 'sum',
        items: ['w1', 'w2', 'w3', 'w4', 'w5'],
        bands: [
          { max: 7, label: 'Lower than your own usual, by today’s answers', tone: 'attention' },
          { min: 8, max: 14, label: 'Around your own middle today', tone: 'neutral' },
          { min: 15, label: 'Brighter than your own usual today', tone: 'positive' },
        ],
        bandFraming:
          'These bands describe your own answers today against our own informal splits. They are not a diagnosis, not a screen, and not a clinical threshold.',
      },
    ],
  },
  framing: {
    intro: 'This is a self-check, not a diagnosis. It reflects only what you entered today. If you are struggling, please reach out to someone you trust or a professional.',
    crisisPosture: 'offline-static',
  },
}
