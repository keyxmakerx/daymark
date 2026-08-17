/*
 * WHAT EACH FIELD IS, AND WHERE THE VALUE COMES FROM.
 *
 * The maintainer, who runs the server, could not identify the fields on the therapist sign-in:
 *
 *   "i don't know what some of these fields even are, maybe you need example text (the
 *    unselectable kind) in the fields, and hover over i icons next to the titles of each field
 *    that explain what they are for. I'm a server admin and even i have no context lol"
 *
 * If the person who deployed it cannot tell, a clinician on their first visit has no chance.
 *
 * ## The thing every one of these explanations must answer
 *
 * Not "what is a relRef" — a definition does not help someone holding a screen they cannot fill
 * in. The useful sentence is **where the value comes from**, because for seven of these nine the
 * answer is the same and nobody has ever said it: *it was in the invitation the owner sent you.*
 * Someone who learns that once can fill the whole form; someone given seven precise definitions
 * still cannot.
 *
 * ## Why `where` is separate from `what`
 *
 * They are different questions and get answered at different moments. `what` is for a reader
 * deciding whether they are on the right screen at all; `where` is for the same reader ten seconds
 * later, hunting for a value. Splitting them lets the renderer lead with the short one.
 *
 * ## Placeholders are examples, never instructions
 *
 * A placeholder saying "Enter your token" is a label that disappears when you need it. These are
 * shaped like the real value — a length, an alphabet, a prefix — so a person can tell at a glance
 * whether what they pasted is the right KIND of thing. They are deliberately obvious fakes
 * (`example.com`, repeated characters) so nobody can mistake one for a working value, and the
 * visible <label> always remains: a placeholder is never the only label.
 *
 * This module holds no secrets and no real values. Every string here is authored copy.
 */

/** Every field this module can explain. Named for the input, not for the screen it appears on. */
export type FieldId =
  | 'serverUrl'
  | 'inboxToken'
  | 'relRef'
  | 'credentialId'
  | 'pinnedOwnerSignPub'
  | 'ownerBoxPub'
  | 'wrappedKey'
  | 'totpCode'
  | 'readingPassphrase'
  | 'syncPassphrase'
  | 'accessToken'
  | 'recoveryEmail'

export interface FieldHelp {
  /** The visible label. Sentence case, no jargon in the first three words. */
  label: string
  /** Example-shaped, never an instruction. See the header note. */
  placeholder: string
  /** One sentence: what this value is. */
  what: string
  /** One sentence: where the person gets it. The load-bearing half. */
  where: string
  /**
   * True when the value is a secret that must not be echoed on screen.
   *
   * Drives `type="password"`, and it is data rather than a per-call-site decision so that a new
   * screen rendering the same field cannot quietly show it in the clear.
   */
  secret: boolean
}

/** The invitation is the answer to "where" for most of the therapist's fields. Said once, here. */
const FROM_INVITE =
  'It was in the invitation the person whose data this is sent you. Everything except your ' +
  'authenticator code and your reading passphrase comes from that one message.'

export const FIELD_HELP: Record<FieldId, FieldHelp> = {
  serverUrl: {
    label: 'Server address',
    placeholder: 'https://daymark.example.com',
    what: 'The address of the Daymark server you were invited to. Nothing is sent anywhere else.',
    where:
      'It is at the top of your invitation. If you were given a link rather than an address, the ' +
      'part before the first slash after the domain is what goes here.',
    secret: false,
  },

  inboxToken: {
    label: 'Inbox token',
    placeholder: 'inbox_XXXXXXXXXXXXXXXXXXXXXXXX',
    what:
      'Routes your requests to the one relationship you were invited to, and to nothing else on ' +
      'that server.',
    where: FROM_INVITE,
    secret: true,
  },

  relRef: {
    label: 'Relationship id',
    placeholder: 'rel_XXXXXXXXXXXXXXXX',
    what:
      'Names the specific owner–clinician relationship. A server can host several; this says ' +
      'which one is yours.',
    where: FROM_INVITE,
    secret: false,
  },

  credentialId: {
    label: 'Credential id',
    placeholder: 'cred_XXXXXXXXXXXX',
    what: 'Identifies you to the server — the equivalent of a username here.',
    where: FROM_INVITE,
    secret: false,
  },

  pinnedOwnerSignPub: {
    label: 'Owner signing key',
    placeholder: 'base64url, about 43 characters',
    what:
      'The key that proves a share really came from the person who invited you. Anything not ' +
      'signed by it is refused rather than shown.',
    where: FROM_INVITE,
    secret: false,
  },

  ownerBoxPub: {
    label: 'Owner encryption key',
    placeholder: 'base64url, about 43 characters',
    what: 'The key anything you send back is sealed to, so only they can open it.',
    where: FROM_INVITE,
    secret: false,
  },

  wrappedKey: {
    label: 'Your wrapped reading key',
    placeholder: '{"v":1,"salt":"…","nonce":"…","ct":"…"}',
    what:
      'Your key to read what was shared, stored locked. It is unlocked in this browser with your ' +
      'reading passphrase and is never sent anywhere.',
    where: FROM_INVITE,
    secret: true,
  },

  totpCode: {
    label: 'Authenticator code',
    placeholder: '000000',
    what: 'The six digits from your authenticator app, which change every thirty seconds.',
    where:
      'From the authenticator app you set up when you first accepted the invitation — not from ' +
      'the invitation itself.',
    secret: false,
  },

  readingPassphrase: {
    label: 'Reading passphrase',
    placeholder: 'the passphrase you chose when you accepted',
    what: 'Unlocks your reading key in this browser. The server never receives it.',
    where:
      'You chose it when you accepted the invitation. Nobody can look it up for you — if it is ' +
      'lost, the owner has to invite you again.',
    secret: true,
  },

  syncPassphrase: {
    label: 'Your passphrase',
    placeholder: 'the passphrase you set in the app',
    what:
      'Unlocks your own backup in this browser. Your entries were encrypted with it before they ' +
      'left your phone, and the server has never had it.',
    where:
      'You set it in the Daymark app when you turned sync on. It is not recoverable from the ' +
      'server, because the server has never seen it.',
    secret: true,
  },

  accessToken: {
    label: 'Access token',
    placeholder: 'a long random string from your server settings',
    what: 'Proves to your server that you are allowed to read and write your own snapshots.',
    where:
      'Whoever set up the server chose it — it is the DAYMARK_AUTH_TOKEN value in the server ' +
      'configuration. If you set the server up yourself, it is the one you generated then.',
    secret: true,
  },

  recoveryEmail: {
    label: 'Email address',
    placeholder: 'you@example.com',
    what: 'Where the recovery link is sent.',
    where:
      'It has to be the address already saved on the server. One that is not saved gets the same ' +
      'answer as one that is, so nobody can use this to discover whether an address is in use.',
    secret: false,
  },
}

/** The ids in a stable order, for tests and for anything rendering the whole set. */
export const FIELD_IDS = Object.keys(FIELD_HELP) as FieldId[]

/**
 * The `id` of the element a field's help panel gets, so `aria-controls` and the panel agree.
 *
 * Derived rather than hand-written per call site: two spellings would break the association
 * silently, and an `aria-controls` pointing at nothing is worse than none — a screen reader
 * announces a control that promises to reveal something and then does not.
 */
export const helpPanelId = (field: FieldId): string => `fieldhelp-${field}`
