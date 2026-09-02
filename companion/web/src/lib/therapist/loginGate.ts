/*
 * LOGIN GATE — what is wrong with the form, in the order a person reads it.
 *
 * WHAT THIS MODULE DECIDES. Given what has been typed into the sign-in form and which of its
 * sections are on screen, the FIRST field a person would have to fix, and the sentence that
 * tells them so. It owns no credential, no key and no request; LoginGate.svelte calls it before
 * touching the network, so the first thing an empty submit says is "Enter your authenticator
 * code" and not the name of an encoding.
 *
 * WHY THE ORDER IS A LIST HERE AND NOT A SEQUENCE OF `if`s IN THE COMPONENT. The form is read
 * top to bottom, and the first complaint should be about the first thing the eye lands on. That
 * is only true if the order the checks run in is the order the fields are drawn in — which is a
 * property of two files, edited months apart. Holding the order as data lets loginGate.test.ts
 * read the component's markup and assert the two agree, so a field moved in the markup without a
 * matching move here is a failing test rather than an alert about a field two screens down.
 *
 * WHY THE MESSAGE NAMES THE FIELD BY ITS LABEL. The label is the one word the person can see
 * and match. It is read from fieldHelp.ts rather than retyped, so a renamed label renames the
 * sentence with it; the test pins that every message contains the label it is about.
 *
 * WHY A PROBLEM SAYS WHETHER IT IS BEHIND THE FOLD. The nine fallback fields live in a
 * disclosure that is closed by default. "Enter the server address" about a field the person
 * cannot see is a riddle, so a problem in that section says so, and the component opens it. The
 * sentence about the fold is composed here, where the test can see it, rather than in markup.
 *
 * NO SVELTE, NO DOM, NO CLOCK. Plain data in, plain data out.
 */

import { FIELD_HELP } from '../onboarding/fieldHelp'

/** The fields the sign-in form can complain about. Owner keys are not here: they are optional
 *  on the manual path (the server usually publishes them), and their absence is reported after
 *  the fetch that would have supplied them. */
export type UnlockFieldId =
  | 'totpCode'
  | 'readingPassphrase'
  | 'serverUrl'
  | 'inboxToken'
  | 'relRef'
  | 'credentialId'
  | 'wrappedKey'

/** What has been typed. Untrimmed; this module trims. */
export interface UnlockValues {
  totpCode: string
  readingPassphrase: string
  serverUrl: string
  inboxToken: string
  relRef: string
  credentialId: string
  wrappedKeyJson: string
}

/** Which parts of the form are on screen, which decides what can be asked for. */
export interface UnlockShape {
  /** The fallback path: every connection value typed by hand. */
  manual: boolean
  /** Stored path only: this browser's record is missing its inbox token, so the field is shown. */
  askInboxToken: boolean
  /** Whether the fallback disclosure is currently open. Only meaningful when `manual`. */
  fallbackOpen: boolean
}

export interface UnlockProblem {
  field: UnlockFieldId
  /** The whole sentence the person is shown. */
  message: string
  /** The field sits inside the fallback disclosure. */
  inFallback: boolean
  /** The disclosure was closed when this was found, so the component should open it. */
  opensFallback: boolean
}

/** The two fields that are always on the form, in the order they are drawn. */
export const PRIMARY_ORDER: readonly UnlockFieldId[] = ['totpCode', 'readingPassphrase']

/** The fallback fields this module can complain about, in the order they are drawn. */
export const FALLBACK_ORDER: readonly UnlockFieldId[] = [
  'serverUrl',
  'inboxToken',
  'relRef',
  'credentialId',
  'wrappedKey',
]

/** What an empty field is told. Each names its field by the label fieldHelp.ts draws. */
const EMPTY: Record<UnlockFieldId, string> = {
  totpCode: `Enter your ${FIELD_HELP.totpCode.label.toLowerCase()}.`,
  readingPassphrase: `Enter your ${FIELD_HELP.readingPassphrase.label.toLowerCase()}.`,
  serverUrl: `Enter the ${FIELD_HELP.serverUrl.label.toLowerCase()}.`,
  inboxToken: `Enter your ${FIELD_HELP.inboxToken.label.toLowerCase()}.`,
  relRef: `Enter the ${FIELD_HELP.relRef.label.toLowerCase()}.`,
  credentialId: `Enter your ${FIELD_HELP.credentialId.label.toLowerCase()}.`,
  wrappedKey: `Paste ${FIELD_HELP.wrappedKey.label.toLowerCase()}.`,
}

/** What the fold is called in a sentence, so a person can find it on the page. */
export const FALLBACK_SECTION_NOTE = 'That field is in the connection details below, which have been opened.'

/**
 * A wrapped key that is present but cannot be read. Exported because the component keeps a
 * defensive parse on the unlock path itself, and the two must say the same thing.
 */
export const WRAPPED_KEY_UNREADABLE =
  `${FIELD_HELP.wrappedKey.label} could not be read. Paste the whole block exactly as it was given to you.`

/**
 * A value that is present but cannot be used. Only the two fields with a checkable shape have
 * one; the rest are opaque identifiers the server is the authority on.
 */
function invalidReason(field: UnlockFieldId, value: string): string | null {
  switch (field) {
    case 'serverUrl': {
      let url: URL
      try {
        url = new URL(value)
      } catch {
        return `The ${FIELD_HELP.serverUrl.label.toLowerCase()} is not a full web address. It starts with https://.`
      }
      if (url.protocol !== 'https:' && url.protocol !== 'http:') {
        return `The ${FIELD_HELP.serverUrl.label.toLowerCase()} is not a web address. It starts with https://.`
      }
      return null
    }
    case 'wrappedKey': {
      try {
        const parsed: unknown = JSON.parse(value)
        if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
          return WRAPPED_KEY_UNREADABLE
        }
        return null
      } catch {
        return WRAPPED_KEY_UNREADABLE
      }
    }
    default:
      return null
  }
}

function valueOf(values: UnlockValues, field: UnlockFieldId): string {
  switch (field) {
    case 'totpCode':
      return values.totpCode
    case 'readingPassphrase':
      return values.readingPassphrase
    case 'serverUrl':
      return values.serverUrl
    case 'inboxToken':
      return values.inboxToken
    case 'relRef':
      return values.relRef
    case 'credentialId':
      return values.credentialId
    case 'wrappedKey':
      return values.wrappedKeyJson
  }
}

/**
 * The fields to check, top to bottom, for this shape of the form. Exposed so the test can
 * compare it against the markup rather than trusting the two to agree.
 */
export function checkOrder(shape: Pick<UnlockShape, 'manual' | 'askInboxToken'>): UnlockFieldId[] {
  if (shape.manual) return [...PRIMARY_ORDER, ...FALLBACK_ORDER]
  return shape.askInboxToken ? [...PRIMARY_ORDER, 'inboxToken'] : [...PRIMARY_ORDER]
}

/**
 * The first field, in reading order, that is empty or cannot be used — or null when the form
 * can be submitted. Trims before judging, so a stray space is not "present".
 */
export function firstProblem(values: UnlockValues, shape: UnlockShape): UnlockProblem | null {
  for (const field of checkOrder(shape)) {
    const raw = valueOf(values, field)
    const value = raw.trim()
    const fault = value === '' ? EMPTY[field] : invalidReason(field, value)
    if (fault === null) continue

    const inFallback = shape.manual && FALLBACK_ORDER.includes(field)
    const opensFallback = inFallback && !shape.fallbackOpen
    const message = opensFallback ? `${fault} ${FALLBACK_SECTION_NOTE}` : fault
    return { field, message, inFallback, opensFallback }
  }
  return null
}
