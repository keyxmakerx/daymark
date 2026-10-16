/*
 * BAND WORDS, AS A CLINICIAN READS THEM (#422).
 *
 * A self-check's band words are recorded with each result and travel in a share, so a clinician
 * sees the person's own second-person sentences ("Lower than your own usual"), addressed to the
 * wrong reader. The recorded label stays what it is: a result is placed on the lane whose words
 * match the label it was recorded with (clinicianRecord's header), so earlier results must keep
 * the words they were recorded under. This maps a recorded label to the clinician's wording at
 * the moment of display and nowhere else; placement and comparison use the recorded words.
 *
 * Every band label in the catalog that says "your" has an entry here; bandWords.test.ts walks the
 * catalog and fails on one that does not.
 */

/** Recorded wording → wording for the clinician. Exact recorded labels, whole. */
export const CLINICIAN_BAND_WORDS: Readonly<Record<string, string>> = {
  'Lower than your own usual, by today’s answers': 'Lower than this person’s own usual, by today’s answers',
  'Around your own middle today': 'Around this person’s own middle today',
  'Brighter than your own usual today': 'Brighter than this person’s own usual today',
  // The scale's framing sentence is shown with the lanes and says "your" the same way.
  'These bands describe your own answers today against our own informal splits.':
    'These bands describe their own answers today against our own informal splits.',
  'These bands describe your own answers today against our own informal splits — not the source instrument’s cutoff.':
    'These bands describe their own answers today against our own informal splits — not the source instrument’s cutoff.',
}

/**
 * The clinician's wording for text that carries a recorded band label, whole or inside a longer
 * line such as "12 · Around your own middle today". Text with no known label is returned as it is.
 */
export function forClinician(text: string): string {
  let out = text
  for (const [recorded, shown] of Object.entries(CLINICIAN_BAND_WORDS)) out = out.split(recorded).join(shown)
  return out
}
