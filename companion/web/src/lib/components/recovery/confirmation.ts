/*
 * THE WRITE-IT-DOWN CHECK — the step that decides whether anybody ever actually recovers anything.
 *
 * ─── WHY A CHECKBOX IS NOT A CONFIRMATION ───────────────────────────────────────────────────────
 *
 * "I have written down my recovery code" with a box beside it measures nothing whatsoever. It is
 * ticked in the same motion, in the same second, by the person who copied thirty characters onto
 * paper and by the person who is looking for the button that makes the screen go away. Both go on
 * to the same next screen, and the difference between them is only discovered years later, by one
 * of them, at the worst possible moment.
 *
 * The cheapest test that separates those two people is to hide the code and ask for two of its
 * groups back. It costs about fifteen seconds. It cannot be satisfied by anybody who did not write
 * the code down, and it fails for exactly two reasons — the paper does not exist, or the paper is
 * wrong — which are the two failures the whole feature is built to prevent.
 *
 * ─── WHY THE CODE IS HIDDEN, AND WHY THAT IS THE ENTIRE POINT ───────────────────────────────────
 *
 * A type-back check with the code still on screen is a typing exercise. It is passed perfectly by
 * somebody reading it off the display, which is precisely the person the check exists to catch. The
 * hiding is not a flourish and not a difficulty setting: it is the mechanism. Everything else here
 * is arithmetic around it.
 *
 * ─── WHY TWO GROUPS, AND WHY CHOSEN AT RANDOM ───────────────────────────────────────────────────
 *
 * Two rather than one, because a single group can be held in short-term memory from the screen a
 * moment ago without any paper being involved at all — thirty seconds of working memory is enough
 * for five characters and is not enough for ten from two separate places in a random string.
 *
 * Two rather than all six, because the check is not the security boundary and never was. The code
 * is already written down or it is not; asking for all thirty characters back only adds a chance to
 * mistype one and be told the copy is wrong when it was right. That failure is worse than useless —
 * it teaches the person that this screen is unreliable, which is the last thing they should believe
 * about a screen that is telling them the truth about losing their data.
 *
 * Chosen at random rather than fixed, because a fixed pair — always the first and the last — is
 * learnable, and somebody who has been through this once could write down two groups instead of
 * six. Random choice also spreads the check across the code, so a person who transcribed the middle
 * carelessly is as likely to be asked about it as one who trailed off at the end.
 *
 * ─── WHAT THIS MODULE REFUSES TO DO ─────────────────────────────────────────────────────────────
 *
 * NO SCORE, NO VERDICT, NO ECHO. The result is a list of booleans, one per asked group. Nothing
 * here returns a count, a proportion, a grade, or the expected value of a group that did not match.
 * A person who mistypes a group needs to be told which group and nothing else: the expected value
 * is the secret they are being checked on, and printing it back at them would turn the check into a
 * hint and the screen into a way of reading the code again without saying so.
 *
 * NO PENALTY, NO LOCKOUT, NO ATTEMPT COUNT. There is nothing to defend here — the code is on the
 * screen this person was just looking at, and a wrong answer means their paper is wrong, which is
 * the outcome we want to find. Counting attempts would turn a helpful discovery into a failure
 * state, and this product does not keep a score of anything a person does.
 *
 * NO DOM, NO CLOCK, NO STORAGE. Pure functions of strings and an injected random source, which is
 * what makes the selection and the comparison testable without a browser.
 */
import { GROUP_SIZE } from '../../recovery/recoveryCode'
import { GROUP_COUNT, normalizeGroup } from './groups'

/** How many groups the confirmation asks for. Two; see the header note for why not one and not six. */
export const CONFIRMATION_GROUPS = 2

/** One asked group and whether what was typed for it matches. No expected value, ever. */
export interface ConfirmationResult {
  /** 1-based group number, as printed on the code. */
  group: number
  matches: boolean
}

/**
 * A uniform number in [0, 1), from the platform CSPRNG where there is one.
 *
 * WHY THE CSPRNG FOR SOMETHING THAT IS NOT A SECRET. Which two groups get asked is not key material
 * and an attacker learns nothing from predicting it — they would still need the code. The reason to
 * use it anyway is smaller and still real: Math.random is seeded per page in ways that have been
 * observed to correlate across rapid successive calls in some engines, and this function is called
 * twice in a row, microseconds apart, to pick two DISTINCT groups. A CSPRNG makes that
 * uninteresting rather than something to reason about.
 *
 * The fallback exists because this module must remain a pure-logic module that runs anywhere,
 * including in a test runner with no `crypto` global. Falling back is acceptable here precisely
 * because the value is not a secret — which is a sentence that would be a bug if it appeared in
 * recoveryCode.ts, and is why the code's own draw does not have a fallback at all.
 */
export function unitRandom(): number {
  const c = (globalThis as { crypto?: Crypto }).crypto
  if (c && typeof c.getRandomValues === 'function') {
    const buf = new Uint32Array(1)
    c.getRandomValues(buf)
    return buf[0] / 2 ** 32
  }
  return Math.random()
}

/**
 * `howMany` distinct 1-based group numbers, in ascending order.
 *
 * Ascending because the person is going to be shown two fields and asked to fill them from a piece
 * of paper they read left to right; asking for group 5 above group 2 makes them work backwards
 * across the page for no reason.
 *
 * The draw is a partial Fisher-Yates over the group indices, which is uniform over combinations
 * given a uniform source and — unlike "pick one, pick another, retry if it collides" — cannot loop.
 * `random` is injected so a test can pin the selection instead of asserting statistics about it.
 */
export function chooseConfirmationGroups(
  groupCount: number = GROUP_COUNT,
  howMany: number = CONFIRMATION_GROUPS,
  random: () => number = unitRandom,
): number[] {
  const wanted = Math.max(0, Math.min(howMany, groupCount))
  const pool = Array.from({ length: groupCount }, (_, i) => i + 1)
  for (let i = 0; i < wanted; i++) {
    // Clamped rather than trusted: an injected `random` that returns exactly 1 (a stub, a badly
    // written fake) would otherwise index one past the end and put `undefined` in the list, which
    // would surface as a confirmation asking for "group undefined".
    const j = i + Math.min(pool.length - i - 1, Math.floor(random() * (pool.length - i)))
    const swap = pool[j]
    pool[j] = pool[i]
    pool[i] = swap
  }
  return pool.slice(0, wanted).sort((a, b) => a - b)
}

/** The five symbols of one 1-based group of a canonical code. */
export function groupOfCode(canonical: string, group: number): string {
  return canonical.slice((group - 1) * GROUP_SIZE, group * GROUP_SIZE)
}

/**
 * Whether each asked group was typed back correctly.
 *
 * Normalised on both sides, so lower case, stray spaces and the hyphen somebody types out of habit
 * are not treated as mistakes — none of them is a transcription error, and reporting them as one
 * would send a person to check a piece of paper that is perfectly correct.
 *
 * NOT normalised in the Crockford sense: an O typed where a 0 was written stays a mismatch, because
 * the alphabet contains neither and the person has genuinely misread their own handwriting. That is
 * exactly the thing this check is for.
 *
 * A missing answer is a mismatch rather than an error. Half a confirmation is not a confirmation,
 * and the caller has a form to render, not an exception to handle.
 */
export function checkWrittenDown(
  canonical: string,
  asked: number[],
  answers: string[],
): ConfirmationResult[] {
  return asked.map((group, i) => ({
    group,
    matches: normalizeGroup(answers[i] ?? '') === groupOfCode(canonical, group),
  }))
}

/** True only when every asked group matched. There is no partial credit and no score. */
export function confirmationIsComplete(results: ConfirmationResult[]): boolean {
  return results.length > 0 && results.every((r) => r.matches)
}

/**
 * The first group that did not match, or null.
 *
 * First rather than all, for the same reason firstGroupProblem() reports one problem: a person
 * looking at two complaints has to decide which to act on, and the answer is always "the first
 * one", because they are going to re-read the paper either way.
 */
export function firstMismatch(results: ConfirmationResult[]): number | null {
  const miss = results.find((r) => !r.matches)
  return miss ? miss.group : null
}
