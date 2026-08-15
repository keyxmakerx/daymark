import type { InstrumentDefinition } from '../types'

/*
 * GUIDED EXERCISES — the first unscored definitions in the catalog.
 *
 * These are not questionnaires. There is no scale, no score, no band, and nothing here is
 * shared with anyone: every writing item carries `excludeFromScoring`, which also keeps it out
 * of any future share. `scoring.scales` is deliberately empty, which the validator accepts —
 * an instrument may measure nothing and still be a legitimate thing to hand someone.
 *
 * WHY THESE TWO AND NOT AN AFFIRMATION DECK (docs/EVIDENCE_REVIEW_2026-08.md §2).
 * "Affirmation" names at least three different practices and the popular one is the weakest:
 *
 *   - Positive self-statements ("I am enough"). Wood, Perunovic & Lee (2009) found people with
 *     low self-esteem felt WORSE after repeating them. A later study failed to replicate that,
 *     so the harm is contested — but nothing demonstrates a benefit either. Unproven upside plus
 *     a plausible downside is a bad trade for the population this app serves.
 *   - Values affirmation (writing about what matters to you). Modest but real: a 2025
 *     meta-analysis over 129 tests found significant if small well-being gains.
 *   - Self-compassion. The best evidenced of the three (g ~ 0.66 on depression), and Compassion
 *     Focused Therapy was developed specifically for people with shame and self-criticism.
 *
 * THE STRUCTURAL RULE these exercises are built to satisfy, which holds regardless of how the
 * replication argument settles, because it rests on self-verification theory rather than on any
 * single result:
 *
 *     Never require the person to assert a positive claim about themselves that they do not
 *     believe.
 *
 * Nothing below asks anyone to say they are good, lovable, or enough. "Hard moments happen to
 * people" is not a claim about your worth, so there is nothing to contradict. That is the whole
 * design, and it is why these are safe to hand to someone at their lowest without a gate.
 *
 * Original wording throughout. These draw on compassion-focused and values-clarification methods
 * but reproduce no published script or licensed instrument, so they are honestly `custom` — the
 * most conservative tier, which self-discloses and can never pose as clinical.
 */

/** A hard moment — the friend reframe, without asking anyone to praise themselves. */
export const hardMomentExercise: InstrumentDefinition = {
  instrumentId: 'compassion-hard-moment',
  instrumentVersion: '1.0.0',
  title: 'A hard moment',
  license: 'Self-authored (original wording) — GPL-3.0. Draws on compassion-focused practice; reproduces no published script.',
  ledgerRef: 'INSTRUMENTS.md#compassion-hard-moment',
  provenance: { tier: 'custom' },
  nonDiagnostic: true,
  noScreeningFlag: true,
  estimatedMinutes: 4,
  framing: {
    intro:
      'A few minutes with something that has been difficult. This is a personal reflection exercise — not a diagnosis, not a screen, and not a clinical assessment. Nothing here is scored, and nothing you write leaves your phone. You can stop at any point, and stopping partway is a perfectly normal way to use this.',
  },
  items: [
    {
      id: 'intro',
      type: 'info',
      body: 'Bring to mind something that has felt heavy lately. It does not have to be the biggest thing — just something real. You do not have to write it down if you would rather not.',
    },
    {
      id: 'what',
      type: 'freeText',
      prompt: 'What has been sitting with you? As much or as little as you want.',
      excludeFromScoring: true,
    },
    {
      id: 'common',
      type: 'info',
      body: 'Difficult stretches are part of being a person. That is not a consolation prize, and it does not make yours smaller — it just means you are not doing something wrong by finding this hard.',
    },
    {
      id: 'friend',
      type: 'freeText',
      prompt: 'If someone you cared about told you exactly this, what would you say to them?',
      excludeFromScoring: true,
    },
    {
      id: 'bridge',
      type: 'info',
      body: 'Read that back. You do not have to believe it about yourself yet, or agree with it, or feel any different. Noticing the gap between what you would offer someone else and what you offer yourself is the whole exercise.',
    },
    {
      id: 'need',
      type: 'freeText',
      prompt: 'Is there anything small you need today? It is fine if the answer is nothing, or if it is something you cannot have.',
      excludeFromScoring: true,
    },
    {
      id: 'close',
      type: 'info',
      body: 'That is the end. Nothing is scored, nothing is filed, and nothing follows up unless you ask it to.',
    },
  ],
  scoring: { scales: [] },
}

/** What matters — values writing. Not about the self, which is precisely why it is safe. */
export const valuesExercise: InstrumentDefinition = {
  instrumentId: 'values-what-matters',
  instrumentVersion: '1.0.0',
  title: 'What matters to you',
  license: 'Self-authored (original wording) — GPL-3.0. Draws on values-clarification methods; reproduces no published instrument.',
  ledgerRef: 'INSTRUMENTS.md#values-what-matters',
  provenance: { tier: 'custom' },
  nonDiagnostic: true,
  noScreeningFlag: true,
  estimatedMinutes: 5,
  framing: {
    intro:
      'A short piece of writing about what you care about — not about how you are doing. This is a personal reflection exercise, not a diagnosis, screen, or clinical assessment. Nothing is scored and nothing leaves your phone. There are no right answers, and nobody is going to read it.',
  },
  items: [
    {
      id: 'intro',
      type: 'info',
      body: 'This one is not about you, exactly. It is about the things you would still care about on a day when you did not care about much. Pick one and write about it for a few minutes.',
    },
    {
      id: 'pick',
      type: 'singleSelect',
      prompt: 'Which of these is closest to something that matters to you?',
      options: [
        { id: 'v1', label: 'The people I am close to', value: 0 },
        { id: 'v2', label: 'Being someone others can rely on', value: 1 },
        { id: 'v3', label: 'Making or building things', value: 2 },
        { id: 'v4', label: 'Learning and understanding', value: 3 },
        { id: 'v5', label: 'Fairness, or looking after people', value: 4 },
        { id: 'v6', label: 'Something not listed here', value: 5 },
      ],
      excludeFromScoring: true,
    },
    {
      id: 'why',
      type: 'freeText',
      prompt: 'Why does that one matter to you? A time it showed up is often easier to write than a reason.',
      excludeFromScoring: true,
    },
    {
      id: 'small',
      type: 'freeText',
      prompt: 'When did you last act on it, even in a very small way?',
      excludeFromScoring: true,
    },
    {
      id: 'close',
      type: 'info',
      body: 'That is all of it. Noticing what you value is not a plan and does not oblige you to do anything about it today.',
    },
  ],
  scoring: { scales: [] },
}
