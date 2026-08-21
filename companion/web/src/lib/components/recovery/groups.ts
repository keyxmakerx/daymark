/*
 * READING A CODE BACK IN SIX GROUPS — turning "that is not a valid code" into "there is a mistake in
 * group 3, at the second character".
 *
 * ─── WHY THIS MODULE EXISTS SEPARATELY FROM recoveryCode.ts ─────────────────────────────────────
 *
 * recoveryCode.ts diagnoses a code as a STRING: it reports a fault and, where the fault has one, a
 * 1-based position in the thirty canonical symbols. That is the right shape for a crypto module and
 * the wrong shape for a person, for one specific reason — the position is an index into the code
 * with the hyphens removed, and nobody looking at a piece of paper can count to 17 across six
 * groups without losing their place. The whole reason the code is printed in groups of five is that
 * a person navigates it by group.
 *
 * So this module owns the translation, and it does it by keeping the entry itself grouped: six
 * fields, each holding its own five symbols, checked in their own terms. Position arithmetic over a
 * concatenated string would be wrong the moment one group is short — with four characters in group
 * 1, the seventh canonical symbol is in group 2 on screen and group 1 by index, and the interface
 * would confidently point at the wrong box.
 *
 * ─── THE ORDER OF DIAGNOSIS, AND WHY IT IS NOT recoveryCode.ts's ORDER ──────────────────────────
 *
 * parseRecoveryCode checks characters, then length, then the check symbol, and explains why in its
 * own comment. This module keeps that order but applies the first two per group, left to right:
 *
 *   1. A character that cannot be in a code. Positioned exactly — group and offset — because the
 *      alphabet gave up 0, O, 1, I and L precisely so that seeing one is a definite error at a
 *      definite place rather than an ambiguity to be resolved by guessing.
 *   2. A group that is not five symbols. Positioned to the group.
 *   3. Only when all six groups are clean and complete: the check character, over the whole code.
 *
 * Steps 1 and 2 run before step 3 because a check character computed over the wrong number of
 * symbols is meaningless, and because a person with a character problem is better served by being
 * told which character than by being told the code does not add up.
 *
 * ─── THE CHECK CHARACTER'S FAILURE HAS NO POSITION, AND THIS MODULE DOES NOT INVENT ONE ────────
 *
 * The obvious next feature here is the dishonest one. The check symbol is a weighted sum mod 31,
 * and its syndrome can be solved for a correction at every one of the twenty-nine positions at
 * once: each position has some symbol value that would make the sum come out right. So there is no
 * "the mistake is in group 3" available on this path — only "the mistake is somewhere", which is
 * exactly what recoveryCode.ts says it will report and exactly what it refuses to embellish ("NO
 * CORRECTION, ONLY DETECTION... a guess dressed as help").
 *
 * Naming a group anyway would be worse than saying nothing. It would send somebody to rewrite a
 * character that was right, on the authority of a screen that sounded certain, while the actual
 * mistake sat two groups away. The `group` field is therefore null on that branch, and the copy
 * that goes with it says in as many words that the check cannot locate the error.
 *
 * ─── NO DOM, NO CLOCK, NO NETWORK, NO ECHO ─────────────────────────────────────────────────────
 *
 * Everything here is a pure function of an array of strings, which is what makes the positioned
 * diagnoses testable exhaustively rather than by inspection. And nothing this module returns ever
 * contains any part of what was typed: positions are reported, characters are not. Same rule as
 * recoveryCode.ts and inviteLink.ts, for the same reason — a fault message is the thing most likely
 * to be screenshotted into a support thread, and half of what it would be quoting is a live key.
 */
import {
  GROUP_SIZE,
  CODE_SYMBOLS,
  RECOVERY_ALPHABET,
  RECOVERY_FAULT_TEXT,
  normalizeRecoveryInput,
  parseRecoveryCode,
  type RecoveryCode,
} from '../../recovery/recoveryCode'
import { CHECKSUM_CANNOT_POINT, groupLengthIsWrong, mistakeInGroup } from './copy'

/** Six groups of five. Derived rather than written, so it tracks the code it displays. */
export const GROUP_COUNT = CODE_SYMBOLS / GROUP_SIZE

/** The five characters the alphabet excludes so that seeing one is a diagnosis, not an ambiguity. */
const NEVER_IN_A_CODE = new Set(['0', 'O', '1', 'I', 'L'])

/** One thing wrong with what was typed, in the terms the person is looking at it in. */
export interface GroupProblem {
  /** Which kind, so a caller can style or test on it without matching prose. */
  kind: 'empty' | 'confusable' | 'notInAlphabet' | 'length' | 'checksum'
  /** 1-based group, or null when the fault genuinely has no position — see the header note. */
  group: number | null
  /** 1-based offset inside that group, when the fault is a single character. */
  positionInGroup: number | null
  /** The sentence a person reads. Never contains any part of what was typed. */
  message: string
  /** The second sentence, where one is owed. Null when the first says everything. */
  detail: string | null
}

/** Six empty strings — the starting state of a grouped entry field. */
export function emptyGroups(): string[] {
  return Array.from({ length: GROUP_COUNT }, () => '')
}

/**
 * One group's text, as the alphabet sees it: separators dropped, upper-cased, nothing folded.
 *
 * Deliberately reuses normalizeRecoveryInput rather than doing its own `toUpperCase()`, so that a
 * change to what counts as a separator — the Unicode dashes a code picks up on its way through a
 * word processor, say — lands in one place. In particular O is NOT folded to 0 and I/L are NOT
 * folded to 1: that is the Crockford behaviour this alphabet does not need, and doing it anyway
 * would turn a character this module can diagnose exactly into a silent substitution.
 */
export function normalizeGroup(text: string): string {
  return normalizeRecoveryInput(text)
}

/**
 * Spread pasted text across the group fields, starting at the field it was pasted into.
 *
 * The paste case is not a nicety. A person who was given their code as a file, or who copied it out
 * of a password manager on the way to somewhere better, arrives with all thirty characters and one
 * cursor. Without this they get five characters in one box and twenty-five discarded, and the
 * interface then reports a length problem in every group after it — a wall of complaints about an
 * input that was completely correct.
 *
 * Returns a new array; the caller's is untouched.
 */
export function distributeIntoGroups(groups: string[], index: number, pasted: string): string[] {
  const next = [...groups]
  const symbols = normalizeGroup(pasted)
  // A paste that fits in the field it landed in is not a spread — leave the other fields alone, so
  // correcting one group by pasting five characters into it does not wipe the other five groups.
  if (symbols.length <= GROUP_SIZE) {
    next[index] = symbols
    return next
  }
  for (let i = index, at = 0; i < next.length && at < symbols.length; i++, at += GROUP_SIZE) {
    next[i] = symbols.slice(at, at + GROUP_SIZE)
  }
  return next
}

/** The six groups as the hyphenated form parseRecoveryCode reads. */
export function groupsToTyped(groups: string[]): string {
  return groups.map(normalizeGroup).join('-')
}

/** True when every group is empty — the state a fresh form is in, which is not an error yet. */
export function groupsAreEmpty(groups: string[]): boolean {
  return groups.every((g) => normalizeGroup(g).length === 0)
}

/**
 * A canonical 1-based symbol position to the group a person would look at.
 *
 * Used for a code that arrived whole — from a paste, or from a caller holding a single string —
 * where there is no per-field structure to read the position off. It is exact only when the code is
 * the right length overall, which is why the grouped path above prefers per-group checks.
 */
export function groupOfPosition(at: number): { group: number; positionInGroup: number } {
  return {
    group: Math.floor((at - 1) / GROUP_SIZE) + 1,
    positionInGroup: ((at - 1) % GROUP_SIZE) + 1,
  }
}

/**
 * The first thing wrong with what was typed, or null when the six groups are a code.
 *
 * FIRST rather than all of them, deliberately. A person mid-recovery reading six simultaneous
 * complaints has to decide which one to act on, and the first one is nearly always the cause of the
 * rest — a dropped character makes the group short, which makes the next group's contents look
 * wrong, and so on down the code.
 */
export function firstGroupProblem(groups: string[]): GroupProblem | null {
  if (groupsAreEmpty(groups)) {
    return {
      kind: 'empty',
      group: null,
      positionInGroup: null,
      message: RECOVERY_FAULT_TEXT.empty,
      detail: null,
    }
  }

  // (1) Characters, left to right across the whole code. Both branches are positioned exactly.
  for (let g = 0; g < groups.length; g++) {
    const symbols = normalizeGroup(groups[g])
    for (let i = 0; i < symbols.length; i++) {
      const ch = symbols[i]
      if (NEVER_IN_A_CODE.has(ch)) {
        return {
          kind: 'confusable',
          group: g + 1,
          positionInGroup: i + 1,
          message: mistakeInGroup(g + 1, i + 1),
          detail: RECOVERY_FAULT_TEXT.confusable,
        }
      }
      if (!RECOVERY_ALPHABET.includes(ch)) {
        return {
          kind: 'notInAlphabet',
          group: g + 1,
          positionInGroup: i + 1,
          message: mistakeInGroup(g + 1, i + 1),
          detail: RECOVERY_FAULT_TEXT.notInAlphabet,
        }
      }
    }
  }

  // (2) Lengths, per group. Checked after characters so that a group containing an O is reported as
  // the O rather than as "five characters, one of which we will not name".
  for (let g = 0; g < groups.length; g++) {
    const symbols = normalizeGroup(groups[g])
    if (symbols.length !== GROUP_SIZE) {
      return {
        kind: 'length',
        group: g + 1,
        positionInGroup: null,
        message: groupLengthIsWrong(g + 1, symbols.length),
        detail: null,
      }
    }
  }

  // (3) The check character, over a code that is now known to be well-formed and the right length.
  // Any remaining fault from the parser is the checksum one; the earlier branches are unreachable
  // from here, and are mapped rather than asserted so that a future fault kind cannot fall through
  // this function as a null and be read by the caller as "that is a valid code".
  const parsed = parseRecoveryCode(groupsToTyped(groups))
  if (parsed.ok) return null
  return {
    kind: 'checksum',
    // Null on purpose, and the one field in this module with a long comment above it. The check
    // symbol proves a mistake exists and cannot say where; see the header note.
    group: null,
    positionInGroup: null,
    message: RECOVERY_FAULT_TEXT[parsed.fault],
    detail: parsed.fault === 'checksum' ? CHECKSUM_CANNOT_POINT : null,
  }
}

/** The parsed code, when the six groups are one — otherwise null. Never throws. */
export function codeFromGroups(groups: string[]): RecoveryCode | null {
  if (firstGroupProblem(groups) !== null) return null
  const parsed = parseRecoveryCode(groupsToTyped(groups))
  return parsed.ok ? parsed.code : null
}
