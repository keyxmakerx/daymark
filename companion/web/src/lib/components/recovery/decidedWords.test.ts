/*
 * THE WORDS DECIDED FOR THE OWNER'S KEY (#258), PINNED — AND THE WORDS THEY REPLACED, GONE.
 *
 * When the owner's key moved onto the server, the sentences around it were reviewed as one set: one
 * term for each thing, and every message saying what a person can do next. This file holds the set
 * in place:
 *
 *   (a) every sentence the review changed or kept, exactly;
 *   (b) every sentence it replaced, absent from everything that ships — each search first shown
 *       finding a planted copy, because a search that cannot see the old words proves nothing;
 *   (c) the terms: no "key parameters" or "key material" where a person reads, never "copy" or
 *       "slot" for a lock, the full "recovery code" in any changed sentence that says "code", "this
 *       server" in the verb that reads it, one wording for a wait, and a tab named by its own label;
 *   (d) the layout the review decided: exactly one line directly above a recovery code, and a
 *       "Read what this server holds" button directly under every message that asks for a read.
 *
 * Two places depart from the review's text on purpose, and are pinned as they ship: NOTHING_TO_OPEN
 * names the tab by its real label ("Get a code") and drops "yet", because it is the body of an empty
 * state and "yet" is banned from every one (#103, components/ui/emptyState.test.ts).
 */
import { describe, it, expect } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import * as copy from './copy'
import * as unlockCopy from '../../owner/unlockCopy'
import { UNLOCK_FAULT_TEXT } from '../../owner/unlock'
import {
  HOLDS_NO_KEY,
  KEY_CHANGED_BEFORE_UPLOAD,
  PASSPHRASE_DOES_NOT_OPEN_KEY,
  SNAPSHOTS_WITHOUT_KEY,
} from '../../sync/client'

const SRC = fileURLToPath(new URL('../../../', import.meta.url))
const read = (fromSrc: string) => readFileSync(SRC + fromSrc, 'utf8')

/** Markup and script with commentary removed: what ships, not what explains it. */
const codeOf = (text: string) =>
  text
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(?<!:)\/\/[^\n]*/g, '')

const door = codeOf(read('lib/components/owner/OwnerUnlock.svelte'))
const getCode = codeOf(read('lib/components/recovery/NewCodeFlow.svelte'))
const useCode = codeOf(read('lib/components/recovery/UseCodeFlow.svelte'))
const keySetup = codeOf(read('lib/components/recovery/KeySetup.svelte'))
const sheet = codeOf(read('lib/components/recovery/CodeSheet.svelte'))
const panel = codeOf(read('lib/components/recovery/RecoveryPanel.svelte'))
const syncPanel = codeOf(read('lib/components/SyncPanel.svelte'))
const clientSource = codeOf(read('lib/sync/client.ts'))

/** Whitespace folded to single spaces, so markup that wraps across lines reads as one sentence. */
const fold = (s: string) => s.replace(/\s+/g, ' ')

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (a) The decided sentences, exactly.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** Every sentence the review changed, as it ships. */
const CHANGED: [name: string, actual: string, decided: string][] = [
  // components/recovery/copy.ts
  ['WHERE_THE_KEY_IS', copy.WHERE_THE_KEY_IS,
    'Your key is kept on your server, locked twice: once under your passphrase and once under your recovery code. Either one opens it. The server can open neither lock. The two tabs below read and write those locks using the server address and access token in the card above.'],
  ['CODE_DOES_NOT_OPEN_THIS', copy.CODE_DOES_NOT_OPEN_THIS,
    'That recovery code does not open the key this server holds. Nothing has changed. Compare it with what you wrote down, one character at a time. A code for a different key looks no different from one with a mistake in it.'],
  ['NEW_PASSPHRASE_LEDE', copy.NEW_PASSPHRASE_LEDE,
    'The key is open. Setting a new passphrase locks this same key again, under the new passphrase, and stores that lock on the server in place of the old one. The key does not change, so nothing encrypted under it needs encrypting again.'],
  ['PASSPHRASE_CHANGE_IS_NOT_A_REVOCATION', copy.PASSPHRASE_CHANGE_IS_NOT_A_REVOCATION,
    'The key itself did not change. This server now hands out only the lock made with the new passphrase. A backup of the server taken before now still holds the old lock, and the old passphrase still opens that. This console cannot yet replace the key itself.'],
  ['PASSPHRASE_REPLACED', copy.PASSPHRASE_REPLACED,
    'The server now holds your key locked under the new passphrase. The key itself did not change, so nothing encrypted under it needs encrypting again.'],
  ['REPLACE_MOVED', copy.REPLACE_MOVED,
    'What the server holds changed while the key was open here, so the new passphrase was not stored. Open the key again with your recovery code.'],
  ['REPLACE_UNCHECKED', copy.REPLACE_UNCHECKED,
    'The server accepted the new lock, but what it handed back did not open this key. Do not rely on the new passphrase yet. Your snapshots are unchanged. Read what this server holds again to see which passphrase opens it.'],
  ['REPLACE_FAILED', copy.REPLACE_FAILED,
    'This screen could not finish storing the new passphrase. Read what this server holds again to see which passphrase opens it.'],
  ['READ_BUSY', copy.READ_BUSY, 'Reading what this server holds'],
  ['TOKEN_NOT_ACCEPTED', copy.TOKEN_NOT_ACCEPTED, 'This server did not accept that access token, so nothing was read.'],
  ['READ_FAILED', copy.READ_FAILED, 'What this server holds could not be read, so nothing has changed.'],
  ['ALREADY_LOCKED_HERE', copy.ALREADY_LOCKED_HERE,
    'This server already holds your key, locked under a passphrase and a recovery code, so there is nothing to make here. To use the recovery code you already have, go to the Use a code tab.'],
  ['KEY_STORED_HERE', copy.KEY_STORED_HERE,
    'Your key is on your server now, locked under your passphrase and under the recovery code you wrote down.'],
  ['NOTHING_TO_OPEN', copy.NOTHING_TO_OPEN,
    'This server holds no locked key, so there is no recovery code to use here. The Get a code tab makes the key and its recovery code.'],
  ['HOLDS_KEY_PARAMETERS', copy.HOLDS_KEY_PARAMETERS,
    'This server holds what your passphrase needs to open your snapshots, but no recovery code yet. Adding one locks the key your passphrase already opens, once under that passphrase and once under a new recovery code, and stores those two locks here. Nothing already stored is encrypted again.'],
  ['HOLDS_NOTHING', copy.HOLDS_NOTHING,
    'This server holds no key yet. Choosing a passphrase here makes a new key in this tab, locks it under that passphrase and under a new recovery code, and stores only the two locks on the server. The server can open neither.'],
  ['ENROL_TRIES_NEWEST_SNAPSHOT', copy.ENROL_TRIES_NEWEST_SNAPSHOT,
    'Your passphrase is tried on the newest snapshot this server stores. If the passphrase does not open that snapshot, nothing is stored.'],
  ['ENROL_ASKS_TWICE', copy.ENROL_ASKS_TWICE,
    'This server stores no snapshot to check your passphrase against, so you are asked to type it twice instead.'],
  ['FIRST_RUN_BUSY', copy.FIRST_RUN_BUSY, 'Making and locking your key — this takes a few seconds'],
  ['ENROL_BUSY', copy.ENROL_BUSY, 'Checking and locking your key — this takes a few seconds'],
  ['SETUP_FAULT_TEXT.doesNotOpenNewest', copy.SETUP_FAULT_TEXT.doesNotOpenNewest,
    'That passphrase does not open the newest snapshot on this server, so nothing has been stored. Your snapshots are unchanged.'],
  ['SETUP_FAULT_TEXT.snapshotsWithoutKey', copy.SETUP_FAULT_TEXT.snapshotsWithoutKey,
    'This server stores snapshots but not what is needed to open them. A new key would not open those snapshots, so none was made, and nothing has been stored.'],
  ['SETUP_FAULT_TEXT.selfCheckFailed', copy.SETUP_FAULT_TEXT.selfCheckFailed,
    'The locks made in this tab did not open back to the same key, so nothing has been stored. You can try again.'],
  ['READ_BACK_DID_NOT_MATCH', copy.READ_BACK_DID_NOT_MATCH,
    'The server accepted the new key, but what it handed back did not open to the same key, so the recovery code is not shown. Your snapshots are unchanged. Read what this server holds again to see where things stand.'],
  ['READ_BACK_FAILED', copy.READ_BACK_FAILED,
    'The server accepted your key, but it could not be read back to check just now. Write your recovery code down, then use Read what this server holds to check it.'],
  ['SETUP_FAILED', copy.SETUP_FAILED, 'The key could not be set up. Read what this server holds again to see where things stand.'],
  ['ONLY_TIME_SHOWN', copy.ONLY_TIME_SHOWN, 'Once you leave this page, it cannot be shown again. Write it down before you go on.'],
  // owner/unlockCopy.ts
  ['KEY_IS_ON_THE_SERVER', unlockCopy.KEY_IS_ON_THE_SERVER,
    'This console reads your key from your server, where it is kept locked twice: once under your passphrase and once under your recovery code. Either one opens it. The server can open neither lock. The key is opened here, in this tab, and only for this session.'],
  ['CONNECT_BUSY', unlockCopy.CONNECT_BUSY, 'Reading what this server holds'],
  ['CONNECT_REFUSED', unlockCopy.CONNECT_REFUSED, 'This server did not accept that access token, so nothing was read.'],
  ['CONNECT_FAILED', unlockCopy.CONNECT_FAILED, 'This console could not read what this server holds, so nothing has been unlocked.'],
  ['KEY_STORED_WITH_THIS_CODE', unlockCopy.KEY_STORED_WITH_THIS_CODE,
    'Your key is on your server now, locked under your passphrase and under the recovery code above.'],
  // owner/unlock.ts: one refusal per kind of lock that is missing
  ['UNLOCK_FAULT_TEXT.noRecoveryLock', UNLOCK_FAULT_TEXT.noRecoveryLock,
    'This server holds no recovery code lock for your key, so a recovery code cannot open it. Use your passphrase.'],
  ['UNLOCK_FAULT_TEXT.noPassphraseLock', UNLOCK_FAULT_TEXT.noPassphraseLock,
    'This server holds no passphrase lock for your key, so a passphrase cannot open it. Use your recovery code.'],
  ['UNLOCK_FAULT_TEXT.didNotOpen', UNLOCK_FAULT_TEXT.didNotOpen,
    'That did not open the key this server holds. Nothing has changed. Check what you typed and try again.'],
  // sync/client.ts, which the sync card and `pnpm push` show as they stand
  ['SNAPSHOTS_WITHOUT_KEY', SNAPSHOTS_WITHOUT_KEY,
    'This server stores snapshots but not what is needed to open them. A new key would not open those snapshots, so none was made, and nothing has been stored.'],
  ['HOLDS_NO_KEY', HOLDS_NO_KEY, 'This server holds no key: nothing has been synced to it.'],
]

/** Every sentence the review kept, as it ships. Pinned too: kept is a decision, not an omission. */
const KEPT: [name: string, actual: string, decided: string][] = [
  ['CODE_CAN_ACT_AS_YOU', copy.CODE_CAN_ACT_AS_YOU,
    'Whoever holds this code can open your data and act as you, exactly as your passphrase can. Keep the paper where only you can reach it.'],
  ['KEY_CHANGED_ON_SERVER', copy.KEY_CHANGED_ON_SERVER,
    'What the server holds changed after it was read here, so nothing from here was stored. What it holds now is below.'],
  ['SETUP_FAULT_TEXT.noPassphrase', copy.SETUP_FAULT_TEXT.noPassphrase, 'Enter a passphrase. Nothing has been stored.'],
  ['SETUP_FAULT_TEXT.typeItTwice', copy.SETUP_FAULT_TEXT.typeItTwice, 'Enter the passphrase a second time. Nothing has been stored.'],
  ['SETUP_FAULT_TEXT.passphrasesDiffer', copy.SETUP_FAULT_TEXT.passphrasesDiffer, 'The two passphrases are different. Nothing has been stored.'],
  ['SETUP_FAULT_TEXT.alreadyLocked', copy.SETUP_FAULT_TEXT.alreadyLocked, 'This server already holds a locked key, so nothing has been stored.'],
  ['HOLDS_A_LOCKED_KEY', unlockCopy.HOLDS_A_LOCKED_KEY, 'This server holds your key, locked. Open it with your passphrase or your recovery code.'],
  ['NOTHING_IS_KEPT', unlockCopy.NOTHING_IS_KEPT,
    'Nothing here is kept between visits: not your key, and not the identity it gives. When this tab closes they are gone, and the console asks again next time.'],
  ['FINGERPRINT_IS_STABLE', unlockCopy.FINGERPRINT_IS_STABLE,
    'It is the same every time, whether your passphrase or your recovery code opened the key. A clinician who wrote it down can check it against what they see.'],
  ['FIRST_RUN_ACTION', copy.FIRST_RUN_ACTION, 'Make my key'],
  ['ENROL_ACTION', copy.ENROL_ACTION, 'Add a recovery code'],
  ['CONNECT_ACTION', unlockCopy.CONNECT_ACTION, 'Connect'],
  ['READ_ACTION', copy.READ_ACTION, 'Read what this server holds'],
  ['UNLOCK_BUSY', unlockCopy.UNLOCK_BUSY, 'Opening your key — this takes a few seconds'],
  ['KEY_CHANGED_BEFORE_UPLOAD', KEY_CHANGED_BEFORE_UPLOAD,
    'The snapshot was not sent. The key this server holds changed while the snapshot was being encrypted, and this passphrase does not open it to the key the snapshot was encrypted under.'],
]

/** Sentences written into the markup rather than a copy module, as a person reads them. */
const INLINE: [where: string, source: string, decided: string][] = [
  ['UseCodeFlow.svelte, the locks counted', useCode,
    "The key this server holds has {slots.passphrase} passphrase {slots.passphrase === 1 ? 'lock' : 'locks'} and {slots.recovery} recovery code {slots.recovery === 1 ? 'lock' : 'locks'}. Any one of them opens the same key."],
  ['UseCodeFlow.svelte, the button that opens', useCode, "{busy ? 'Deriving the key — this takes a few seconds' : 'Open the key'}"],
  ['UseCodeFlow.svelte, the button that locks', useCode,
    "{busy ? 'Locking and storing the key — this takes a few seconds' : 'Lock the key under this passphrase'}"],
  ['NewCodeFlow.svelte, the last step', getCode,
    'The recovery code is no longer in this page. Nothing here can show it again, and nothing can reconstruct it from the locked key — which is exactly why a locked key can sit on a server that never learns anything from holding it.'],
  ['SyncPanel.svelte, the lede under "Recovery code"', syncPanel,
    'Your snapshots open with the passphrase above, or with your recovery code once you have made one.'],
]

describe('(a) the decided sentences, exactly', () => {
  it('every changed sentence ships as decided', () => {
    for (const [name, actual, decided] of CHANGED) expect(actual, name).toBe(decided)
  })

  it('every kept sentence ships as it was', () => {
    for (const [name, actual, decided] of KEPT) expect(actual, name).toBe(decided)
  })

  it('the sentences written into the markup ship as decided', () => {
    for (const [where, source, decided] of INLINE) expect(fold(source), where).toContain(decided)
  })

  it('the writer and the set-up form refuse a server with snapshots and no key in the same words', () => {
    expect(SNAPSHOTS_WITHOUT_KEY).toBe(copy.SETUP_FAULT_TEXT.snapshotsWithoutKey)
  })

  it('the key is said to open with either secret wherever it is said to be locked twice', () => {
    for (const text of [copy.WHERE_THE_KEY_IS, unlockCopy.KEY_IS_ON_THE_SERVER]) {
      expect(text).toContain('locked twice')
      expect(text).toContain('Either one opens it.')
    }
  })

  it('a refusal that stored nothing says the snapshots are unchanged wherever it could be read as a loss', () => {
    for (const text of [copy.REPLACE_UNCHECKED, copy.SETUP_FAULT_TEXT.doesNotOpenNewest, copy.READ_BACK_DID_NOT_MATCH]) {
      expect(text).toContain('Your snapshots are unchanged.')
    }
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (b) The sentences the decided ones replaced, gone.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** Each replaced sentence, word for word as it shipped before. Each was a whole string literal. */
const REPLACED: string[] = [
  'Your key is kept on your server, locked twice: once under your passphrase and once under your recovery code. The server can open neither lock. The two tabs below read the locks from it, and write them, with the address and access token in the card above.',
  'That code is well-formed and does not open the key this server holds. Either it belongs to a different key, or a character is wrong in a way the check character could not catch.',
  'The key is open. Setting a passphrase locks this same key again, under the new passphrase, and stores that lock on the server in place of the old one. The key does not change, so nothing encrypted under it needs encrypting again.',
  'The key itself did not change, so everything encrypted under it still opens with it. This server now hands out only the copy locked under the new passphrase, but a copy of the server made before now, such as a backup, still opens with the old one. Replacing the key itself is not built yet.',
  'The passphrase copy of your key on the server was replaced. The key itself did not change, so nothing encrypted under it needs encrypting again.',
  'The key on the server changed while it was open here, so the new passphrase was not stored. Open it again with your code.',
  'The server took the new passphrase copy, and what it handed back did not open to the same key. Read what the server holds again before relying on the new passphrase.',
  'This screen could not finish storing the new passphrase. Read what the server holds again to see which passphrase opens it.',
  'Reading what the server holds',
  'The server did not accept that access token, so nothing was read.',
  'What the server holds could not be read, so nothing has changed.',
  'This server already holds your key, locked under a passphrase and a recovery code, so there is no new code to make here. Using the code you have is the other tab.',
  'Your key is on your server now, locked under your passphrase and under the code you wrote down.',
  'This server holds no locked key, so there is no recovery code to use with it. Getting a code, in the other tab, makes one.',
  'This server holds the key parameters your snapshots were written with, and no recovery code yet. Adding a recovery code locks the key your passphrase already opens, once under the passphrase and once under a new code, and stores the two locks here. Nothing already stored is encrypted again.',
  'This server holds no key yet. Choosing a passphrase here makes your key in this tab, locks it under the passphrase and under a new recovery code, and stores only the two locks on the server.',
  'Your passphrase is tried first on the newest snapshot this server stores. If it does not open it, nothing is stored.',
  'This server stores no snapshot to try your passphrase on, so it is asked for twice instead.',
  'Making and locking your key — this takes several seconds',
  'Checking and locking your key — this takes several seconds',
  'That passphrase does not open the newest snapshot on this server, so nothing has been stored.',
  'This server stores snapshots and no key parameters for them. A new key would not open them, so none was made, and nothing has been stored.',
  'The locked key made in this tab did not open again to the same key, so nothing has been stored.',
  'The server took the new key, and what it handed back did not open to the same key, so no recovery code is shown. Read what the server holds again to see where things stand.',
  'The key could not be set up. Read what the server holds again to see where things stand.',
  'This console reads your key from your server, where it is kept locked twice: once under your passphrase and once under your recovery code. The server can open neither lock. The key is opened here, in this tab, and only for this session.',
  'This console could not read what the server holds, so nothing has been unlocked.',
  'Your key is on the server now, locked under your passphrase and under the recovery code below.',
  'The key this server holds has no copy locked that way.',
  'That did not open the key this server holds. It is worth checking what you typed.',
  'Nothing was stored. This server stores snapshots but not what is needed to open them. A new key would not open those snapshots, so none was made.',
  'the server sent key parameters this client cannot read',
  'keyparams store failed',
  'the server refused the key parameters and holds no key document',
  'no key parameters on server — nothing has been synced yet',
  // ONLY_TIME_SHOWN's first wording, which contradicted SHOWING_AGAIN_IS_FINE (the code can be shown
  // again while the page is open).
  'This is the only time it is shown. Write it down before you go on.',
]

/** Replaced markup, as fragments no decided sentence contains, in folded whitespace. */
const REPLACED_MARKUP: string[] = [
  "passphrase {slots.passphrase === 1 ? 'copy' : 'copies'}",
  'Opening any one of them opens the same key.',
  'The code is no longer in this page.',
  'The passphrase above is the only way into your snapshots.',
  'title="What the old passphrase still opens"',
  'title="That code did not open this key"',
]

/** Every shipped source file: .ts and .svelte under src, tests excepted. */
function shipped(dir = SRC): string[] {
  const out: string[] = []
  for (const name of readdirSync(dir)) {
    const full = dir + name
    if (statSync(full).isDirectory()) out.push(...shipped(full + '/'))
    else if ((name.endsWith('.ts') || name.endsWith('.svelte')) && !name.endsWith('.test.ts')) out.push(full)
  }
  return out
}

/**
 * A source as its string literals read: commentary gone, a literal split across lines with `+`
 * joined back into one, and whitespace folded. The copy modules write every long sentence as two
 * or three literals, so a search of the raw text for a whole sentence would find nothing even when
 * the sentence ships.
 */
const asLiterals = (text: string) => fold(codeOf(text).replace(/'\s*\+\s*'/g, '').replace(/"\s*\+\s*"/g, ''))

/** Whether a sentence ships as a whole string literal, in any quote. */
const shipsAsLiteral = (sources: string, sentence: string) =>
  [`'${sentence}'`, `"${sentence}"`, `\`${sentence}\``].some((quoted) => sources.includes(quoted))

const SHIPPED = shipped().map((f) => [f, asLiterals(readFileSync(f, 'utf8'))] as const)

describe('(b) the replaced sentences are gone from everything that ships', () => {
  it('the search reads the tree, and finds a replaced sentence planted the way the copy modules write one', () => {
    expect(SHIPPED.length).toBeGreaterThan(100)
    expect(SHIPPED.some(([f]) => f.endsWith('components/recovery/copy.ts'))).toBe(true)
    // Planted into a copy of the module that held it, split across two literals as that module does.
    const planted = asLiterals(
      read('lib/components/recovery/copy.ts') +
        "\nexport const PLANTED =\n  'The key on the server changed while it was open here, so the new passphrase was not stored. ' +\n  'Open it again with your code.'\n",
    )
    expect(shipsAsLiteral(planted, REPLACED[5]!)).toBe(true)
    // And a replaced sentence that a decided one begins with is still told apart: the old one was a
    // whole literal, and the decided one goes on.
    const oldPrefix = 'That passphrase does not open the newest snapshot on this server, so nothing has been stored.'
    expect(copy.SETUP_FAULT_TEXT.doesNotOpenNewest.startsWith(oldPrefix)).toBe(true)
    expect(shipsAsLiteral(asLiterals(read('lib/components/recovery/copy.ts')), oldPrefix)).toBe(false)
    expect(shipsAsLiteral(asLiterals(`x = '${oldPrefix}'`), oldPrefix)).toBe(true)
  })

  it('no replaced sentence ships as a string', () => {
    const found = REPLACED.flatMap((sentence) =>
      SHIPPED.filter(([, text]) => shipsAsLiteral(text, sentence)).map(([f]) => `${f.slice(SRC.length)}: ${sentence.slice(0, 60)}`),
    )
    expect(found).toEqual([])
  })

  it('no replaced sentence is the value of anything the copy modules export', () => {
    const values = [
      ...Object.values(copy).filter((v): v is string => typeof v === 'string'),
      ...Object.values(copy.SETUP_FAULT_TEXT),
      ...Object.values(unlockCopy).filter((v): v is string => typeof v === 'string'),
      ...Object.values(UNLOCK_FAULT_TEXT),
    ]
    expect(values.length).toBeGreaterThan(80)
    expect(values).toContain(copy.READ_ACTION)
    for (const sentence of REPLACED) expect(values, sentence).not.toContain(sentence)
  })

  it('no replaced markup ships, the search first shown finding it', () => {
    const planted = fold(`<Callout tone="warn" title="What the old passphrase still opens">`)
    expect(planted.includes(REPLACED_MARKUP[4]!)).toBe(true)
    const markup = shipped().filter((f) => f.endsWith('.svelte')).map((f) => [f, fold(codeOf(readFileSync(f, 'utf8')))] as const)
    expect(markup.length).toBeGreaterThan(40)
    const found = REPLACED_MARKUP.flatMap((fragment) => markup.filter(([, text]) => text.includes(fragment)).map(([f]) => `${f.slice(SRC.length)}: ${fragment}`))
    expect(found).toEqual([])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (c) The terms.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/**
 * The text a person reads in markup: tags and control blocks gone, and each interpolation replaced
 * by the words it can put on screen — the string literals in it, such as both arms of
 * `{n === 1 ? 'lock' : 'locks'}`. Dropping interpolations whole would hide exactly those words.
 */
const proseOf = (markup: string) =>
  fold(
    markup
      .replace(/<script[\s\S]*?<\/script>/g, '')
      .replace(/<style[\s\S]*?<\/style>/g, '')
      .replace(/\{[#:/@][^}]*\}/g, ' ')
      .replace(/\{([^}]*)\}/g, (_, inner: string) => ` ${[...inner.matchAll(/'([^']*)'/g)].map((m) => m[1]).join(' ')} `)
      .replace(/<[^>]*>/g, ' '),
  )
const titlesOf = (markup: string) => [...markup.replace(/<script[\s\S]*?<\/script>/g, '').matchAll(/\s(?:title|legend)="([^"]{3,})"/g)].map((m) => m[1]!)
/** The sentences a script assigns or returns: literals that start with a capital and run to four words. */
const scriptSentencesOf = (source: string) =>
  [...codeOf(source).matchAll(/'([A-Z][^'\n\\]{15,})'/g)].map((m) => m[1]!).filter((s) => s.split(' ').length >= 4)

const COMPONENTS = ['OwnerUnlock.svelte', 'NewCodeFlow.svelte', 'UseCodeFlow.svelte', 'KeySetup.svelte', 'CodeSheet.svelte', 'RecoveryPanel.svelte', 'WriteDownCheck.svelte', 'GroupEntry.svelte', 'Placeholder.svelte']
const componentSource = (file: string) =>
  read(file === 'OwnerUnlock.svelte' ? 'lib/components/owner/OwnerUnlock.svelte' : `lib/components/recovery/${file}`)

/** Every sentence a person can read on these screens, the sync card, and from `pnpm push`. */
const READ_BY_A_PERSON: string[] = [
  ...Object.values(copy).filter((v): v is string => typeof v === 'string'),
  ...Object.values(copy.SETUP_FAULT_TEXT),
  ...copy.PLACEHOLDERS.flatMap((p) => [p.title, p.body]),
  ...Object.values(unlockCopy).filter((v): v is string => typeof v === 'string'),
  ...Object.values(UNLOCK_FAULT_TEXT),
  PASSPHRASE_DOES_NOT_OPEN_KEY,
  SNAPSHOTS_WITHOUT_KEY,
  KEY_CHANGED_BEFORE_UPLOAD,
  HOLDS_NO_KEY,
  // What the client throws is shown as it stands, by the sync card and by `pnpm push`.
  ...[...clientSource.matchAll(/new SyncError\('([^']+)'/g)].map((m) => m[1]!),
  ...COMPONENTS.flatMap((f) => [proseOf(codeOf(componentSource(f))), ...titlesOf(componentSource(f)), ...scriptSentencesOf(componentSource(f))]),
  proseOf(syncPanel),
]

const TERMS: { name: string; pattern: RegExp; planted: string }[] = [
  { name: '"key parameters"', pattern: /\bkey[ -]?param(?:eter)?s\b|\bkeyparams\b/i, planted: 'This server holds the key parameters your snapshots were written with.' },
  { name: '"key material"', pattern: /\bkey material\b/i, planted: 'Your key material is kept on the server.' },
  {
    name: '"copy" for a lock',
    pattern: /\b(?:passphrase|recovery(?: code)?) cop(?:y|ies)\b|\bcop(?:y|ies) locked\b|\bcop(?:y|ies) of (?:your|the|this) key\b/i,
    planted: 'The server now hands out only the copy locked under the new passphrase.',
  },
  { name: '"slot"', pattern: /\bslots?\b/i, planted: 'This server holds no recovery slot for your key.' },
  { name: '"the server" in the verb that reads it', pattern: /\bread(?:ing)? what the server holds\b/i, planted: 'Read what the server holds again to see where things stand.' },
  { name: 'a wait worded twice', pattern: /several seconds/i, planted: 'Making and locking your key — this takes several seconds' },
]

describe('(c) one term for each thing, wherever a person reads it', () => {
  it('reads the sentences it is about, including the words an interpolation puts on screen', () => {
    expect(proseOf("<p>Locked in {n} passphrase {n === 1 ? 'copy' : 'copies'}.</p>")).toBe(' Locked in passphrase copy copies . ')
    expect(READ_BY_A_PERSON.some((s) => s.includes("Deriving the key — this takes a few seconds Open the key"))).toBe(true)
    expect(READ_BY_A_PERSON.length).toBeGreaterThan(120)
    expect(READ_BY_A_PERSON).toContain(HOLDS_NO_KEY)
    expect(READ_BY_A_PERSON).toContain('the server sent a key document this client cannot read')
    expect(READ_BY_A_PERSON.some((s) => s.includes('Any one of them opens the same key.'))).toBe(true)
    expect(READ_BY_A_PERSON).toContain('Where your key is')
  })

  it('each detector catches its planted sentence, and none fires on a decided one', () => {
    for (const { name, pattern, planted } of TERMS) {
      expect(pattern.test(planted), name).toBe(true)
      for (const [decided, , text] of CHANGED) expect(pattern.test(text), `${name}: ${decided}`).toBe(false)
    }
    // The planted old sentences are real: each is one the decided set replaced.
    expect(REPLACED.some((s) => TERMS[2]!.pattern.test(s))).toBe(true)
    expect(REPLACED.some((s) => TERMS[0]!.pattern.test(s))).toBe(true)
  })

  it('no sentence a person reads uses a retired term', () => {
    const found = TERMS.flatMap(({ name, pattern }) => READ_BY_A_PERSON.filter((s) => pattern.test(s)).map((s) => `${name}: ${s.slice(0, 80)}`))
    expect(found).toEqual([])
  })

  it('a changed sentence that says "code" also says "recovery code", or names a tab', () => {
    // Bare "code" only where the full term is in the same message, or in a tab's own name.
    const BARE = /(?<!recovery |Use a |Get a )\bcode\b/i
    const unclear = (s: string) => BARE.test(s) && !s.includes('recovery code')
    expect(unclear('Open it again with your code.')).toBe(true)
    expect(unclear('go to the Use a code tab')).toBe(false)
    const found = [...CHANGED, ...INLINE.map(([w, , d]) => [w, d, d] as const)].filter(([, , text]) => unclear(text)).map(([n]) => n)
    expect(found).toEqual([])
  })

  it('a tab is named by the label it has', () => {
    const tablist = panel.slice(panel.indexOf('role="tablist"'), panel.indexOf('role="tabpanel"'))
    const labels = [...tablist.matchAll(/>\s*([A-Za-z][^<>{}]*?)\s*<\/button>/g)].map((m) => m[1]!)
    expect(labels).toEqual(['Get a code', 'Use a code'])
    const NAMED = /\b[Tt]he ([A-Z][a-z]*(?: [a-z]+)*) tab\b/g
    const named = (s: string) => [...s.matchAll(NAMED)].map((m) => m[1]!)
    expect(named('To use it, go to the Make my key tab.')).toEqual(['Make my key'])
    // The Recovery code screen's own sentences: the owner console names its own tabs elsewhere.
    const recoveryScreen = [
      ...Object.values(copy).filter((v): v is string => typeof v === 'string'),
      ...Object.values(copy.SETUP_FAULT_TEXT),
      ...copy.PLACEHOLDERS.flatMap((p) => [p.title, p.body]),
      ...['NewCodeFlow.svelte', 'UseCodeFlow.svelte', 'KeySetup.svelte', 'RecoveryPanel.svelte'].map((f) => proseOf(codeOf(componentSource(f)))),
    ]
    const mentions = recoveryScreen.flatMap(named)
    expect(mentions.sort()).toEqual(['Get a code', 'Use a code'])
    for (const tab of mentions) expect(labels, tab).toContain(tab)
  })

  it('every wait on Argon2id ends in the same words, and the read says only what it does', () => {
    const waits = [copy.FIRST_RUN_BUSY, copy.ENROL_BUSY, unlockCopy.UNLOCK_BUSY, ...[...useCode.matchAll(/busy \? '([^']+)'/g)].map((m) => m[1]!)]
    expect(waits).toHaveLength(5)
    for (const wait of waits) expect(wait, wait).toMatch(/^[A-Z][a-z]+ [^—]+ — this takes a few seconds$/)
    for (const reading of [copy.READ_BUSY, unlockCopy.CONNECT_BUSY]) expect(reading).toBe('Reading what this server holds')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (d) What sits above a recovery code, and what sits under a message that asks for a read.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(d) one line above the code', () => {
  const SHEET_TOP = /<div class="sheet">\s*<p class="printed-only">\{WHAT_THIS_OPENS\}<\/p>\s*<p class="screen-only">\{ONLY_TIME_SHOWN\}<\/p>\s*<div class="groups/
  const DOOR_TOP = /\{#if codeStep === 'showing'\}\s*<CodeSheet display=\{newCode\.display\} \/>/
  const FLOW_TOP = /<Card title="Your recovery code">\s*<div class="stack">\s*<CodeSheet display=\{code\.display\} \/>/

  it('the sheet draws ONLY_TIME_SHOWN directly above the groups, on screen and not on paper', () => {
    expect(sheet).toMatch(SHEET_TOP)
    // Not drawn on screen: the printed heading. Not drawn on paper: the line.
    expect(sheet).toMatch(/\.printed-only \{\s*display: none;/)
    const print = sheet.slice(sheet.indexOf('@media print'))
    expect(print).toMatch(/\.screen-only \{\s*display: none;/)
    // Positive control: a second line planted above the groups is seen.
    expect(sheet.replace('<div class="groups', '<p class="once">{SHOWN_ONCE}</p>\n  <div class="groups')).not.toMatch(SHEET_TOP)
  })

  it('both screens that show a code put nothing of their own between their heading and the sheet', () => {
    expect(door).toMatch(DOOR_TOP)
    expect(getCode).toMatch(FLOW_TOP)
    // The positive control: the arrangement this replaced is seen, on both.
    expect(door.replace('<CodeSheet', '<p class="hint">{WRITE_IT_ON_PAPER}</p>\n<CodeSheet')).not.toMatch(DOOR_TOP)
    expect(getCode.replace('<CodeSheet', '<p class="para">{WRITE_IT_ON_PAPER}</p>\n<CodeSheet')).not.toMatch(FLOW_TOP)
    // And these are the only two places a code is shown.
    const sheets = shipped().filter((f) => f.endsWith('.svelte') && /<CodeSheet\b/.test(codeOf(readFileSync(f, 'utf8'))))
    expect(sheets.map((f) => f.slice(SRC.length)).sort()).toEqual([
      'lib/components/owner/OwnerUnlock.svelte',
      'lib/components/recovery/NewCodeFlow.svelte',
    ])
  })

  it('what the screens say about the code comes after it', () => {
    for (const [screen, markup, sheetTag] of [
      ['door', door, '<CodeSheet display={newCode.display} />'],
      ['Get a code', getCode, '<CodeSheet display={code.display} />'],
    ] as const) {
      expect(markup.indexOf('{WRITE_IT_ON_PAPER}'), screen).toBeGreaterThan(markup.indexOf(sheetTag))
    }
    expect(door.indexOf('{KEY_STORED_WITH_THIS_CODE}')).toBeGreaterThan(door.indexOf('<CodeSheet'))
  })
})

describe('(d) a read button under every message that asks for a read', () => {
  const ASKS = /\bRead what this server holds\b/

  it('READS_AGAIN is exactly the messages that ask for a read', () => {
    expect(ASKS.test(copy.SETUP_FAILED)).toBe(true)
    // The button's own label names the action and asks for nothing.
    const asking = [...new Set(READ_BY_A_PERSON.filter((s) => s !== copy.READ_ACTION && ASKS.test(s)))].sort()
    expect(asking).toEqual([...copy.READS_AGAIN].sort())
    expect(copy.READS_AGAIN.size).toBe(5)
    // KEY_CHANGED_ON_SERVER asks for none: what the server holds was read again, and is below it.
    expect(copy.READS_AGAIN.has(copy.KEY_CHANGED_ON_SERVER)).toBe(false)
    expect(ASKS.test(copy.KEY_CHANGED_ON_SERVER)).toBe(false)
  })

  it('the door: the button is under its one message area whenever the message asks, and under READ_BACK_FAILED', () => {
    expect(door.match(/\{error\}/g)).toHaveLength(1)
    expect(door).toContain('const asksForARead = $derived(READS_AGAIN.has(error))')
    const UNDER = /<Callout tone=\{errorTone\}><p class="para">\{error\}<\/p><\/Callout>\s*\{#if asksForARead\}\s*<button type="button" class="again" onclick=\{connect\}[^>]*>\{busy \? READ_BUSY : READ_ACTION\}<\/button>/
    expect(door).toMatch(UNDER)
    expect(door.replace('{#if asksForARead}', '{#if false}')).not.toMatch(UNDER)
    expect(door).toMatch(/\{READ_BACK_FAILED\}<\/p><\/Callout>\s*<button type="button" onclick=\{checkReadBack\}[^>]*>\{checking \? READ_BUSY : READ_ACTION\}/)
  })

  it('"Get a code": its one message area sits directly above the read button', () => {
    expect(getCode.match(/\{error\}/g)).toHaveLength(1)
    const UNDER = /\{#if error\}\s*<Callout tone="critical">\s*<p class="para">\{error\}<\/p>\s*<\/Callout>\s*\{\/if\}\s*<div class="actions">\s*<button type="button" class="primary" onclick=\{read\}[^>]*>\s*\{busy \? READ_BUSY : READ_ACTION\}/
    expect(getCode).toMatch(UNDER)
    // The arrangement this replaced, message under the button, is seen.
    const swapped = getCode.replace(/(\{#if error\}[\s\S]*?\{\/if\})\s*(<div class="actions">[\s\S]*?<\/div>)/, '$2\n$1')
    expect(swapped).not.toBe(getCode)
    expect(swapped).not.toMatch(UNDER)
    // A set-up that ends where things stand is not known comes back to that step.
    const lost = getCode.slice(getCode.indexOf('function keyLost('))
    expect(lost.slice(0, lost.indexOf('}'))).toContain("step = 'start'")
    // READ_BACK_FAILED, wherever it is drawn, has its check under it.
    const drawn = getCode.match(/\{READ_BACK_FAILED\}/g) ?? []
    const checked = getCode.match(/\{READ_BACK_FAILED\}<\/p><\/Callout>\s*<div class="actions">\s*<button type="button" onclick=\{checkReadBack\}/g) ?? []
    expect(drawn.length).toBe(2)
    expect(checked.length).toBe(drawn.length)
  })

  it('"Use a code": the button is under the message whenever the message asks, and reads', () => {
    const UNDER = /\{:else if fault\}\s*<Callout tone="warn">\s*<p class="para">\{fault\}<\/p>\s*<\/Callout>\s*\{\/if\}\s*<\/div>\s*\{#if !problem && !codeFault && READS_AGAIN\.has\(fault\)\}\s*<div class="actions">\s*<button type="button" onclick=\{readAgain\}[^>]*>\{readingAgain \? READ_BUSY : READ_ACTION\}/
    expect(useCode).toMatch(UNDER)
    expect(useCode.replace('READS_AGAIN.has(fault)', 'false')).not.toMatch(UNDER)
    // Each message that asks is set on the step that draws the button.
    for (const name of ['REPLACE_UNCHECKED', 'REPLACE_FAILED']) {
      const sets = [...useCode.matchAll(new RegExp(`fault = ${name}\\n\\s*step = '(\\w+)'`, 'g'))].map((m) => m[1])
      expect(sets.length, name).toBeGreaterThan(0)
      expect(new Set(sets), name).toEqual(new Set(['entry']))
    }
    // The button compares with what was sent, and only a match moves the screen on.
    const handler = useCode.slice(useCode.indexOf('async function readAgain()'), useCode.indexOf('</script>'))
    expect(handler).toContain('confirmStored(ports, sent)')
    expect(handler).toMatch(/out\.kind === 'held'\)\s*\{\s*sent = null\s*fault = ''\s*step = 'rewrapped'/)
  })

  it('the set-up form draws none of them itself: it hands each to the screen that has the button', () => {
    for (const name of ['READ_BACK_DID_NOT_MATCH', 'SETUP_FAILED']) {
      expect(keySetup, name).toContain(`onlost(${name})`)
      expect(keySetup, name).not.toContain(`{${name}}`)
      expect(keySetup, name).not.toContain(`error = ${name}`)
    }
    // What it does draw, its refusals, asks for no read.
    for (const text of Object.values(copy.SETUP_FAULT_TEXT)) expect(copy.READS_AGAIN.has(text), text).toBe(false)
    for (const text of Object.values(UNLOCK_FAULT_TEXT)) expect(copy.READS_AGAIN.has(text), text).toBe(false)
  })

  it('the passphrase-change sentence is a callout’s body, never its heading', () => {
    expect(useCode).toMatch(/<Callout tone="warn">\s*<p class="para">\{PASSPHRASE_CHANGE_IS_NOT_A_REVOCATION\}<\/p>/)
    expect(titlesOf(read('lib/components/recovery/UseCodeFlow.svelte'))).not.toContain('What the old passphrase still opens')
  })
})
