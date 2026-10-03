import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import * as copy from './copy'
import { slotSummary } from './session'
import type { RecoverableDataKey } from '../../recovery/dataKey'

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS SUITE IS FOR
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Two things this surface can get wrong that no unit test of a pure function would notice.
 *
 * FIRST: IT COULD LIE BY OMISSION. A piece of paper is worth exactly what it is attached to, and a
 * screen that let somebody write thirty characters down without saying where the key they open is
 * kept — or that implied a part that is not built — would be damaging in a way that surfaces years
 * later, to one person, at the worst moment of their use of this product. So the assertions below
 * check that where the key is kept is said above both flows rather than under one of them, that the
 * stand-in that came before the server kept the key is gone rather than still describing itself
 * (#258), and that every unbuilt thing calls itself a placeholder.
 *
 * SECOND: IT COULD LEAK. A recovery code opens years of somebody's journal, and the places a secret
 * escapes to are not exotic — a console call left in during debugging, a localStorage line added to
 * "keep it across a reload", a clipboard convenience, a fetch to a server that had no business
 * receiving it. Each of those is one line, each looks helpful in review, and none of them fails a
 * functional test. So they are asserted as absences over the source text, with every detector shown
 * catching a planted example first, because a grep-shaped guard that matches nothing reports success
 * on the empty set.
 *
 * WHY IT READS SOURCE AT ALL. The test environment is node and there is no component renderer here.
 * Every property below is a property of the source text — "this sentence is rendered on this
 * screen", "no file in this directory names localStorage" — for which reading the file is the
 * direct test rather than a proxy for one. The same approach is used by lib/practice/console.test.ts
 * and components/invariants.tree.test.ts.
 */

const DIR = fileURLToPath(new URL('./', import.meta.url))

const files = readdirSync(DIR).sort()
const componentFiles = files.filter((f) => f.endsWith('.svelte'))
const moduleFiles = files.filter((f) => f.endsWith('.ts') && !f.endsWith('.test.ts'))
const source = new Map<string, string>(
  [...componentFiles, ...moduleFiles].map((f) => [f, readFileSync(DIR + f, 'utf8')]),
)

/**
 * The one file outside this directory that reaches into it.
 *
 * Kept apart from `source` on purpose: the leak sweep below walks every entry of that map and
 * asserts an absence over it, and SyncPanel is not this surface's file to police. It is read here
 * for exactly one assertion — that the panel is reached lazily — and nothing else.
 */
const syncPanel = readFileSync(fileURLToPath(new URL('../SyncPanel.svelte', import.meta.url)), 'utf8')

/**
 * The prose a person actually reads: script and style gone, comments gone, tags removed.
 *
 * Svelte's control blocks — `{#if}`, `{:else}`, `{/each}` — go too. They are markup rather than
 * words, they never reach a reader, and leaving them in makes the register detectors below fire on
 * `{#if !blob}`: a negation operator read as an exclamation mark. Interpolations like `{PANEL_LEDE}`
 * are deliberately KEPT, because whether a component renders a value from the copy module is
 * exactly what several assertions here are about.
 */
function proseOf(file: string): string {
  return (source.get(file) ?? '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<script[\s\S]*?<\/script>/g, '')
    .replace(/<style[\s\S]*?<\/style>/g, '')
    .replace(/\{[#:/@][^}]*\}/g, ' ')
    .replace(/<[^>]*>/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * Code with commentary removed, for assertions about what a file DOES rather than what it says.
 *
 * The header notes in this directory are long and they name the things they are careful not to do —
 * "no localStorage, no sessionStorage, no clipboard, no fetch". A guard run over raw text would be
 * satisfied by the explanation of the rule instead of by the rule, which is the exact vacuity this
 * suite is shaped to avoid, so the structural assertions run over what ships.
 */
function codeOf(file: string): string {
  return (source.get(file) ?? '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(?<!:)\/\/[^\n]*/g, '')
}

/**
 * The user-facing text that proseOf() cannot see.
 *
 * Two kinds, and both are read by a person exactly as the body copy is:
 *
 *   ATTRIBUTE COPY — `title="Nothing was generated"` on a Callout, `title="Set a passphrase"` on a
 *   Card. proseOf strips tags, so every heading on this surface is invisible to the register
 *   detectors unless it is pulled out separately. A cheerful card title would sail straight past a
 *   suite that only reads between the tags.
 *
 *   SCRIPT COPY — the fault sentences a flow assigns to `error` or `fault`. They live in the script
 *   block, which proseOf removes wholesale, and they are the sentences a person reads at the worst
 *   moment they will have on this screen.
 *
 * Filtered to things that look like prose — a capital letter, several spaces — so that module
 * paths, class names and event names do not drown the corpus.
 */
function attributeCopyOf(file: string): string[] {
  const src = (source.get(file) ?? '').replace(/<script[\s\S]*?<\/script>/g, '')
  return [...src.matchAll(/\stitle="([^"]{4,})"/g)].map((m) => m[1])
}

function scriptCopyOf(file: string): string[] {
  const script = (/<script[\s\S]*?<\/script>/.exec(source.get(file) ?? '')?.[0] ?? '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(?<!:)\/\/[^\n]*/g, '')
  /*
   * The lookbehind is load-bearing rather than decorative. Without it the run between two string
   * literals matches as though it were one — the closing quote of `'./CodeSheet.svelte'` opens a
   * "string" that runs to the next quote several lines later — and the corpus fills with fragments
   * of code. Requiring the opening quote to sit where a literal actually begins (after `=`, `(`,
   * `,`, `:`, `?` or `[`) rules that out, and the newline exclusion keeps a match on one line.
   */
  return [...script.matchAll(/(?<=[=(,:?[]\s*)'([^'\n\\]{20,})'/g)]
    .map((m) => m[1])
    .filter((s) => /^[A-Z]/.test(s) && s.split(' ').length >= 4)
}

/** Every fixed sentence this surface is obliged to say, as one corpus. */
const SENTENCES = [
  ...Object.values(copy).filter((v): v is string => typeof v === 'string' && v.length > 30),
  ...copy.PLACEHOLDERS.map((p) => `${p.title} ${p.body} ${p.specifiedAt}`),
  ...Object.values(copy.SETUP_FAULT_TEXT),
]

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The suite has a subject.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the suite has a subject', () => {
  it('found the surface’s components and its copy', () => {
    expect(componentFiles).toEqual([
      'CodeSheet.svelte',
      'GroupEntry.svelte',
      'KeySetup.svelte',
      'NewCodeFlow.svelte',
      'Placeholder.svelte',
      'RecoveryPanel.svelte',
      'UseCodeFlow.svelte',
      'WriteDownCheck.svelte',
    ])
    expect(moduleFiles).toEqual(['confirmation.ts', 'copy.ts', 'groups.ts', 'index.ts', 'session.ts'])
    expect(SENTENCES.length).toBeGreaterThan(20)
    expect(SENTENCES.every((s) => s.length > 30)).toBe(true)
    for (const file of componentFiles) {
      expect(proseOf(file).length, `${file} rendered no prose`).toBeGreaterThan(20)
    }
  })

  it('the strippers strip', () => {
    // Non-vacuity for both directions. The prose extractor must lose the script; the code extractor
    // must lose the commentary. Session.ts is the witness for the second: its header names every
    // storage API it refuses to touch, and its code names none of them.
    const panel = proseOf('RecoveryPanel.svelte')
    expect(panel).not.toContain('import')
    expect(panel).not.toContain('background:')
    expect(panel).toContain('{PANEL_LEDE}')
    expect(source.get('session.ts')!).toContain('localStorage')
    expect(codeOf('session.ts')).not.toContain('localStorage')
    const removed = [...source.keys()].reduce(
      (n, f) => n + source.get(f)!.length - codeOf(f).length,
      0,
    )
    expect(removed).toBeGreaterThan(15000)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (a) The sentence the paper depends on.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(a) the cost of losing both secrets is stated where it is incurred', () => {
  it('names the three parties who cannot help, rather than saying “nobody”', () => {
    // "Nobody can help you" is read as a formality by people who have watched a support agent reset
    // a password. Naming the mechanism is what makes it land as a description rather than a policy.
    expect(copy.IF_BOTH_ARE_LOST).toContain('Not the server')
    expect(copy.IF_BOTH_ARE_LOST).toContain('Not whoever runs it')
    expect(copy.IF_BOTH_ARE_LOST).toContain('Not the people who wrote this software')
    expect(copy.IF_BOTH_ARE_LOST).toContain('has never held the key')
    // No hedge. "may be", "might not", "could be difficult" are all ways of not saying it.
    expect(copy.IF_BOTH_ARE_LOST).not.toMatch(/\bmay\b|\bmight\b|\bcould\b|\busually\b|\bgenerally\b/i)
  })

  it('renders it where the passphrase is chosen, where the code is shown, and where it is confirmed', () => {
    // The plan is explicit that this belongs where the passphrase is chosen and not in a footnote,
    // and the sheet and the confirmation are the two other moments a person is deciding how
    // seriously to take a piece of paper. A module holding a good sentence is worth nothing if the
    // screens do not render it. KeySetup is where the passphrase is chosen, on both screens.
    for (const file of ['NewCodeFlow.svelte', 'KeySetup.svelte', 'CodeSheet.svelte', 'WriteDownCheck.svelte']) {
      expect(codeOf(file), file).toContain('IF_BOTH_ARE_LOST')
    }
  })

  it('says it is not a password reset, on the screen where somebody would expect one', () => {
    expect(copy.NOT_A_PASSWORD_RESET).toContain('not support')
    expect(copy.NOT_A_PASSWORD_RESET).toContain('not a clinic administrator')
    expect(codeOf('UseCodeFlow.svelte')).toContain('NOT_A_PASSWORD_RESET')
  })

  it('carries its own context onto paper, where the screen is gone', () => {
    // A printed sheet outlives the page it came from and is read by somebody who did not print it.
    expect(copy.WHAT_THIS_OPENS).toContain('Daymark recovery code')
    const sheet = codeOf('CodeSheet.svelte')
    expect(sheet).toContain('WHAT_THIS_OPENS')
    expect(sheet).toContain('@media print')
    // And the downloaded file says the same things, for the same reason.
    expect(codeOf('NewCodeFlow.svelte')).toContain('WHAT_THIS_OPENS')
  })

  it('prints the code in dark ink on white, whatever theme the screen is in (#417)', () => {
    // The print button goes through the switch the month uses (calendar/print.ts), which puts the
    // page in the light theme for the print and back afterwards; print.test.ts holds the order.
    const flow = codeOf('NewCodeFlow.svelte')
    expect(flow).toContain("import { browserPrintPort, printCodeSheet } from '../../calendar/print'")
    expect(flow).toMatch(/<button type="button" onclick=\{\(\) => printCodeSheet\(browserPrintPort\(\)\)\}>Print this page<\/button>/)
    // And no file here opens the print dialog directly, which would print in the screen's theme.
    const BARE = /\bwindow\s*\.\s*print\s*\(/
    expect(BARE.test('<button onclick={() => window.print()}>Print this page</button>')).toBe(true)
    expect(componentFiles.filter((f) => BARE.test(codeOf(f)))).toEqual([])
  })

  it('says, wherever the code is shown and on paper, that it can act as the person', () => {
    // The code opens the master, and the owner's signing identity is derived from it
    // (owner/identity.ts), so the paper is not read-only. Said without naming a console or a
    // clinician, because the sheet is shown on servers that offer neither.
    expect(copy.CODE_CAN_ACT_AS_YOU).toContain('act as you')
    expect(copy.CODE_CAN_ACT_AS_YOU).not.toMatch(/owner console|clinician/i)
    expect(codeOf('CodeSheet.svelte')).toContain('{CODE_CAN_ACT_AS_YOU}')
    expect(codeOf('NewCodeFlow.svelte')).toContain('CODE_CAN_ACT_AS_YOU')
  })

  it('no longer says, on screen or on paper, that a code opens nothing outside the page', () => {
    // The caveat printed on every sheet while no server kept the key would now be false (#258).
    expect('PRINT_SHEET_CAVEAT' in copy).toBe(false)
    for (const file of componentFiles) expect(codeOf(file), file).not.toContain('PRINT_SHEET_CAVEAT')
    const REHEARSAL = /opens nothing outside|rehearsal rather than/i
    expect(REHEARSAL.test('this code opens nothing outside the page it was made in')).toBe(true)
    expect(SENTENCES.filter((s) => REHEARSAL.test(s))).toEqual([])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (b) The confirmation means something.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(b) the write-down check is a check', () => {
  it('is reached from the flow that shows the code, and is not optional', () => {
    const flow = codeOf('NewCodeFlow.svelte')
    expect(flow).toContain('<WriteDownCheck')
    // The only route from 'showing' to the end is through 'confirm'; there is no button that skips
    // it. If one is ever added, this fails.
    expect(flow).toContain("step = 'confirm'")
    expect(flow).not.toMatch(/skip|later|remind me/i)
  })

  it('hides the code before asking for it back', () => {
    // THE mechanism. A type-back check with the code on screen is a typing exercise, passed
    // perfectly by exactly the person it exists to catch.
    const flow = codeOf('NewCodeFlow.svelte')
    expect(flow).toContain("{#if step === 'showing' && code}")
    expect(flow).toContain("{#if step === 'confirm' && code}")
    // The sheet is rendered once, and it is in the 'showing' branch — everything after the confirm
    // branch opens must be free of it.
    expect((flow.match(/<CodeSheet/g) ?? []).length).toBe(1)
    expect(flow.indexOf('<CodeSheet')).toBeLessThan(flow.indexOf("{#if step === 'confirm'"))
    // And the component that does the asking never draws what it is checking against, though it
    // holds it.
    const check = codeOf('WriteDownCheck.svelte')
    expect(check).toContain('canonical')
    expect(check).not.toMatch(/\{canonical\}|\{code\.display\}|\{display\}/)
  })

  it('puts the new code on screen before anything that could fail', () => {
    // The server already holds the lock the code opens; a code that never reached the screen would
    // be a recovery slot nobody holds. So nothing is awaited before it is shown.
    const flow = codeOf('NewCodeFlow.svelte')
    const body = flow.slice(flow.indexOf('async function keyStored('), flow.indexOf('function keyUnread('))
    const shown = body.indexOf('code = stored.recoveryCode')
    expect(shown).toBeGreaterThan(-1)
    expect(body.indexOf('await')).toBeGreaterThan(shown)
  })

  it('shows a code whose lock the server took even when it could not be read back, with the check under the words (#258)', () => {
    const flow = codeOf('NewCodeFlow.svelte')
    const unread = flow.slice(flow.indexOf('function keyUnread('), flow.indexOf('async function checkReadBack('))
    expect(unread).toContain('code = stored.recoveryCode')
    expect(unread).toContain("step = 'showing'")
    // READ_BACK_FAILED, and directly under it the button that checks: on the code's step and on the
    // last one, where the key would otherwise be said to be stored.
    const UNDER = /<Callout tone="warn"><p class="para">\{READ_BACK_FAILED\}<\/p><\/Callout>\s*<div class="actions">\s*<button type="button" onclick=\{checkReadBack\}/g
    expect(flow.match(UNDER)).toHaveLength(2)
    const held = flow.slice(flow.indexOf("{#if step === 'held'}"))
    expect(held).toMatch(/\{#if unread\}[\s\S]*READ_BACK_FAILED[\s\S]*\{:else\}\s*<p class="para">\{KEY_STORED_HERE\}<\/p>/)
    // The check takes the code away only when the server answered with something else.
    const check = flow.slice(flow.indexOf('async function checkReadBack('))
    expect(check.slice(0, check.indexOf('</script>'))).toMatch(/out\.kind === 'other'\)\s*\{\s*code = null/)
  })

  it('drops the code at the end, and offers no way back to it', () => {
    const flow = codeOf('NewCodeFlow.svelte')
    expect(flow).toContain('code = null')
    // The last step has no re-show control: "shown once" is a property of the flow, not a slogan.
    const held = flow.slice(flow.indexOf("{#if step === 'held'}"))
    expect(held).not.toContain('<CodeSheet')
    expect(held).not.toContain("step = 'showing'")
  })

  it('treats showing the code again as ordinary, with no attempt count anywhere', () => {
    // A person who cannot answer has discovered something useful. Counting attempts would turn that
    // discovery into a failure state, and this product keeps no score of anything anybody does.
    expect(copy.SHOWING_AGAIN_IS_FINE).toContain('as many times as you need')
    expect(codeOf('WriteDownCheck.svelte')).toContain('onshowagain')
    const ATTEMPTS = /attempts?\s*(left|remaining|used)|tries left|locked out|too many/i
    expect(ATTEMPTS.test('2 attempts remaining')).toBe(true)
    expect(SENTENCES.filter((s) => ATTEMPTS.test(s))).toEqual([])
    for (const file of componentFiles) expect(ATTEMPTS.test(proseOf(file)), file).toBe(false)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (c) The check character is not given a position it does not have.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(c) a positioned error is positioned, and an unpositioned one says so', () => {
  it('names a group and a character for a fault that has one', () => {
    expect(copy.mistakeInGroup(3, 2)).toBe('There is a mistake in group 3, at the second character.')
    expect(copy.mistakeInGroup(1, 5)).toBe('There is a mistake in group 1, at the fifth character.')
    // Past the fifth character there is no ordinal, and the sentence drops the clause rather than
    // inventing one. It can happen: a group can hold six characters before the length check runs.
    expect(copy.mistakeInGroup(2, 9)).toBe('There is a mistake in group 2.')
  })

  it('explains, in the interface, why the check character cannot point at a group', () => {
    // The one place where the obvious helpful thing is the dishonest thing. recoveryCode.ts refuses
    // to guess ("NO CORRECTION, ONLY DETECTION"); this is that refusal, said to a person.
    expect(copy.CHECKSUM_CANNOT_POINT).toContain('cannot tell us which')
    expect(copy.CHECKSUM_CANNOT_POINT).toContain('guess')
    expect(copy.CHECKSUM_CANNOT_POINT).toContain('Read the whole code back')
    expect(codeOf('groups.ts')).toContain('CHECKSUM_CANNOT_POINT')
  })

  it('renders whichever of the two the diagnosis actually is', () => {
    const flow = codeOf('UseCodeFlow.svelte')
    expect(flow).toContain('firstGroupProblem')
    expect(flow).toContain('problem.message')
    expect(flow).toContain('problem.detail')
    // The entry field carries the group, so the box itself shows which one is meant.
    expect(flow).toContain('problemGroup={problem?.group ?? null}')
    expect(codeOf('GroupEntry.svelte')).toContain("aria-invalid={problemGroup === i + 1}")
  })

  it('checks the shape before it asks the server or derives anything', () => {
    // A mistyped character is refused in milliseconds rather than after three seconds of Argon2id
    // ending in a shrug — the same distinction dataKey.ts builds into unwrapWithRecoveryCode(). And
    // before the read of the server's key, so a typo costs no request either.
    const flow = codeOf('UseCodeFlow.svelte')
    const check = flow.indexOf('firstGroupProblem(groups)')
    expect(check).toBeGreaterThan(-1)
    expect(check).toBeLessThan(flow.indexOf('reading.read()'))
    expect(check).toBeLessThan(flow.indexOf('unwrapWithRecoveryCode'))
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (d) Nothing unbuilt is drawn as though it were built.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(d) where the key is, said before either flow, and nothing unbuilt drawn as built', () => {
  it('says where the key is kept, and what the server can and cannot do with it', () => {
    expect(copy.WHERE_THE_KEY_IS).toContain('kept on your server')
    expect(copy.WHERE_THE_KEY_IS).toContain('The server can open neither lock')
    expect(copy.WHERE_THE_KEY_IS).toContain('the card above')
  })

  it('renders it on the panel, above the choice of flow', () => {
    const panel = codeOf('RecoveryPanel.svelte')
    expect(panel).toContain('{WHERE_THE_KEY_IS}')
    // Above, not under: a person deciding how seriously to take a piece of paper needs to know what
    // it is attached to before the code exists.
    expect(panel.indexOf('WHERE_THE_KEY_IS}')).toBeLessThan(panel.indexOf('<NewCodeFlow'))
    expect(panel.indexOf('WHERE_THE_KEY_IS}')).toBeLessThan(panel.indexOf('role="tablist"'))
  })

  it('the stand-in that came before the server kept the key is gone, words and all (#258)', () => {
    // Each of these said that nothing stores the key, or that the page's memory or a file stood in
    // for storage. With the server keeping the key every one of them would be false, and a false
    // "this opens nothing" is as damaging as a false "this is kept".
    for (const name of [
      'STORAGE_IS_NOT_BUILT',
      'HANDOFF_IS_A_STAND_IN',
      'FILE_IS_A_STAND_IN',
      'FILE_IS_A_STAND_IN_WITHOUT_OWNER_CONSOLE',
      'PANEL_BUILD_STATE',
      'NEW_KEY_NOT_YOUR_ARCHIVE',
    ]) {
      expect(name in copy, name).toBe(false)
      for (const file of componentFiles) expect(codeOf(file), `${file}: ${name}`).not.toContain(name)
    }
    const STAND_IN = /stands? in for storage|nothing stores the wrapped key|no endpoint (that accepts|serves)|in this page’s memory/i
    expect(STAND_IN.test('This stands in for storage; it is not storage.')).toBe(true)
    expect(SENTENCES.filter((s) => STAND_IN.test(s))).toEqual([])
    for (const file of componentFiles) expect(STAND_IN.test(proseOf(file)), file).toBe(false)
  })

  it('the placeholders that are now true went in the same change that made them true', () => {
    const ids = copy.PLACEHOLDERS.map((p) => p.id)
    expect(ids).not.toContain('storage')
    expect(ids).not.toContain('enrolment')
    // Replacing a code is still not built: the server takes a new version, and nothing here makes
    // one. Its note says so rather than saying there is nowhere to publish it.
    const rotation = copy.PLACEHOLDERS.find((p) => p.id === 'rotation')!
    expect(rotation.body).toContain('nothing on this screen makes one yet')
    expect(rotation.body).not.toMatch(/somewhere to publish/)
  })

  it('every placeholder names itself, its subject and where the real thing is specified', () => {
    expect(copy.PLACEHOLDERS.length).toBeGreaterThanOrEqual(3)
    const ids = copy.PLACEHOLDERS.map((p) => p.id)
    expect(new Set(ids).size).toBe(ids.length)
    for (const note of copy.PLACEHOLDERS) {
      expect(note.title.length, note.id).toBeGreaterThan(5)
      // A placeholder that does not say what would be there is a gap with a label on it.
      expect(note.body.length, note.id).toBeGreaterThan(80)
      expect(note.specifiedAt.length, note.id).toBeGreaterThan(10)
    }
  })

  it('the word reaches the interface, on every placeholder, from one place', () => {
    expect(copy.PLACEHOLDER_WORD).toBe('Placeholder')
    expect(codeOf('Placeholder.svelte')).toContain('{PLACEHOLDER_WORD}')
    expect(proseOf('Placeholder.svelte')).toContain('{PLACEHOLDER_WORD}')
    // Rendered through the shared component, so a placeholder cannot be added without the marker.
    for (const file of ['RecoveryPanel.svelte', 'NewCodeFlow.svelte']) {
      expect(codeOf(file), file).toContain('<Placeholder')
    }
    expect(codeOf('RecoveryPanel.svelte')).toContain('{#each notBuilt as note')
  })

  it('promises no dates and no versions', () => {
    // "Coming in the next release" is a claim about a future nobody in this repository controls, and
    // it ages into a lie without anyone editing it.
    const PROMISE = /coming soon|next release|in a future version|by the end of|shipping in|v\d+\.\d+/i
    expect(PROMISE.test('Coming soon in v2.1')).toBe(true)
    expect(SENTENCES.filter((s) => PROMISE.test(s))).toEqual([])
    for (const file of componentFiles) expect(PROMISE.test(proseOf(file)), file).toBe(false)
  })

  it('contains nothing that could pass for real data', () => {
    // The worst failure this surface has after a false statement about storage: a plausible name,
    // date, count or status rendered as though something produced it. Every detector is calibrated
    // on a planted example first.
    const SAMPLE = /\b(jane|john|dr\.|doe|acme|lorem ipsum|example\.com|@example)\b/i
    const FABRICATED = /\b20\d\d-\d\d-\d\d\b|\b\d+ (days?|weeks?|months?|years?) ago\b|last used|last rotated/i
    expect(SAMPLE.test('Dr. Jane Doe')).toBe(true)
    expect(FABRICATED.test('last used 3 days ago')).toBe(true)
    expect(FABRICATED.test('2026-08-21')).toBe(true)
    expect(FABRICATED.test('Read the whole code back')).toBe(false)
    for (const file of componentFiles) {
      expect(SAMPLE.test(proseOf(file)), file).toBe(false)
      expect(FABRICATED.test(proseOf(file)), file).toBe(false)
    }
    expect(SENTENCES.filter((s) => SAMPLE.test(s) || FABRICATED.test(s))).toEqual([])
  })

  it('shows a server with no locked key as an absence rather than as a failure', () => {
    const flow = codeOf('UseCodeFlow.svelte')
    expect(flow).toContain('<EmptyState')
    expect(flow).toContain("{#if held && held.kind !== 'wrapped'}")
    expect(copy.NOTHING_TO_OPEN).toContain('holds no locked key')
  })

  it('states no status the software does not know', () => {
    // No "you have a recovery code", no last-rotated date, no count of codes issued. Nothing
    // anywhere knows any of those, so every one of them would have to be invented — and an invented
    // status on a security screen is indistinguishable from a bug that says everything is fine.
    const CLAIMED_STATE = /you have a recovery code|recovery is (set up|enabled|active)|your code is (saved|stored)/i
    expect(CLAIMED_STATE.test('Recovery is enabled')).toBe(true)
    expect(CLAIMED_STATE.test('Your code is stored')).toBe(true)
    expect(SENTENCES.filter((s) => CLAIMED_STATE.test(s))).toEqual([])
    for (const file of componentFiles) expect(CLAIMED_STATE.test(proseOf(file)), file).toBe(false)
  })

  it('never says the old passphrase stops working, and says what it still opens', () => {
    // The decision on #258: the master does not move, so a changed passphrase is retired against the
    // live server and not against copies someone kept. The reassuring sentence is the false one.
    const FALSE_COMFORT = /old passphrase (no longer|doesn’t|does not|won’t|will not) (work|open)/i
    expect(FALSE_COMFORT.test('Your old passphrase no longer works.')).toBe(true)
    expect(SENTENCES.filter((s) => FALSE_COMFORT.test(s))).toEqual([])
    for (const file of componentFiles) expect(FALSE_COMFORT.test(proseOf(file)), file).toBe(false)
    expect(copy.PASSPHRASE_CHANGE_IS_NOT_A_REVOCATION).toContain('the old passphrase still opens that')
    expect(codeOf('UseCodeFlow.svelte')).toContain('{PASSPHRASE_CHANGE_IS_NOT_A_REVOCATION}')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (e) The register.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(e) the register is flat, adult and non-diagnostic', () => {
  it('claims no health, no pass and no verdict', () => {
    // There is no green in this system and no reassuring verdict available to it. Reassurance is
    // the absence of a callout, not the presence of a friendlier one.
    const CLAIMS = /\ball good\b|\bhealthy\b|\bsecure\b|\bverified\b|\bpassed\b|\bsuccess\b|✓|✔|\bgreen\b/i
    expect(CLAIMS.test('All good — everything is secure.')).toBe(true)
    expect(CLAIMS.test('Read the whole code back against your paper.')).toBe(false)
    expect(SENTENCES.filter((s) => CLAIMS.test(s))).toEqual([])
    for (const file of componentFiles) expect(CLAIMS.test(proseOf(file)), file).toBe(false)
  })

  it('renders no figure, grade, percentage or streak', () => {
    const GAMIFIED = /\b\d+%|\bscore\b|\bstreak\b|\bpoints\b|\bbadge\b|\blevel up\b|\bgrade\b/i
    expect(GAMIFIED.test('Your code scored 92%')).toBe(true)
    expect(SENTENCES.filter((s) => GAMIFIED.test(s))).toEqual([])
    for (const file of componentFiles) expect(GAMIFIED.test(proseOf(file)), file).toBe(false)
  })

  it('keeps the tone flat: no cheer, no exclamation, no emoji', () => {
    // Somebody may be reading the second flow at two in the morning, having lost their passphrase.
    const CHEER = /!|\bwelcome\b|\bgreat\b|\bawesome\b|\bnice work\b|\bwell done\b|\boops\b|\bdon’t worry\b/i
    expect(CHEER.test('Welcome! Great work.')).toBe(true)
    expect(SENTENCES.filter((s) => CHEER.test(s))).toEqual([])
    const EMOJI = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u
    expect(EMOJI.test('done 🎉')).toBe(true)
    for (const file of componentFiles) {
      expect(CHEER.test(proseOf(file)), file).toBe(false)
      expect(EMOJI.test(proseOf(file)), file).toBe(false)
    }
  })

  it('never implies a clinical judgement or describes anybody’s data', () => {
    const CLINICAL = /\bdiagnos|\bsymptom|\bimprov(ing|ed)\b|\bat risk\b|\bconcerning\b/i
    expect(CLINICAL.test('This person is at risk.')).toBe(true)
    expect(SENTENCES.filter((s) => CLINICAL.test(s))).toEqual([])
    for (const file of componentFiles) expect(CLINICAL.test(proseOf(file)), file).toBe(false)
  })

  it('holds the headings and the fault sentences to the same register as the body copy', () => {
    // The gap this closes. proseOf() strips tags, so every Card and Callout title on this surface —
    // and every sentence a flow assigns to `error` — is invisible to the four detectors above. A
    // cheerful heading or a "something went wrong, oops" would pass a suite that only reads between
    // the tags, and headings are the part people actually read.
    const CLAIMS = /\ball good\b|\bhealthy\b|\bsecure\b|\bverified\b|\bpassed\b|\bsuccess\b|✓|✔|\bgreen\b/i
    const CHEER = /!|\bwelcome\b|\bgreat\b|\bawesome\b|\bnice work\b|\bwell done\b|\boops\b/i
    const GAMIFIED = /\b\d+%|\bscore\b|\bstreak\b|\bpoints\b|\bbadge\b|\blevel up\b|\bgrade\b/i
    const EMOJI = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u

    const headings = componentFiles.flatMap(attributeCopyOf)
    const faults = componentFiles.flatMap(scriptCopyOf)
    // Non-vacuity, and it is the whole point of this test: both corpora must actually have found
    // the text they are about. An extractor that returned nothing would report success on an empty
    // set, which is the failure mode every grep-shaped guard has.
    expect(headings.length).toBeGreaterThan(8)
    expect(headings).toContain('Set a passphrase')
    expect(headings).toContain('Where your key is')
    // Most refusals on this surface now come from copy.ts by name; the two a flow still spells out
    // itself are the new-passphrase ones.
    expect(faults.length).toBeGreaterThanOrEqual(2)
    expect(faults.some((s) => s.startsWith('The two passphrases are different'))).toBe(true)

    for (const line of [...headings, ...faults]) {
      expect(CLAIMS.test(line), line).toBe(false)
      expect(CHEER.test(line), line).toBe(false)
      expect(GAMIFIED.test(line), line).toBe(false)
      expect(EMOJI.test(line), line).toBe(false)
    }
  })

  it('says what is not built as placeholders at the foot, rather than letting the panel look finished', () => {
    // Replacing a code, the phone, and a split: each still unbuilt, each said where a reader of the
    // panel will find it.
    expect(codeOf('RecoveryPanel.svelte')).toContain('const notBuilt = PLACEHOLDERS')
    expect(copy.PLACEHOLDERS.map((p) => p.id)).toEqual(['rotation', 'devices', 'split'])
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (f) Nothing writes a code or an open key anywhere.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(f) no recovery code and no unwrapped key leaves this page', () => {
  /** Every API that would move a secret off this tab, or make it survive the tab. */
  const FORBIDDEN: { name: string; pattern: RegExp; planted: string }[] = [
    { name: 'localStorage', pattern: /\blocalStorage\b/, planted: 'localStorage.setItem("k", code)' },
    { name: 'sessionStorage', pattern: /\bsessionStorage\b/, planted: 'sessionStorage.setItem("k", code)' },
    { name: 'indexedDB', pattern: /\bindexedDB\b/i, planted: 'indexedDB.open("codes")' },
    { name: 'cookies', pattern: /document\s*\.\s*cookie/, planted: 'document.cookie = "code=" + code' },
    { name: 'the system clipboard', pattern: /navigator\s*\.\s*clipboard|\.writeText\s*\(/, planted: 'navigator.clipboard.writeText(code)' },
    { name: 'fetch', pattern: /\bfetch\s*\(/, planted: 'fetch("/v1/codes", { body: code })' },
    { name: 'XMLHttpRequest', pattern: /\bXMLHttpRequest\b/, planted: 'new XMLHttpRequest()' },
    { name: 'sendBeacon', pattern: /\bsendBeacon\b/, planted: 'navigator.sendBeacon("/x", code)' },
    { name: 'a websocket', pattern: /\bWebSocket\b|\bEventSource\b/, planted: 'new WebSocket("wss://x")' },
    { name: 'the console', pattern: /\bconsole\s*\.\s*(log|info|warn|error|debug|trace|dir)\b/, planted: 'console.log(code)' },
  ]

  it('the detectors detect', () => {
    // Guard the guards. Ten assertions that each look for an absence are ten ways to be quietly
    // vacuous; every pattern is shown catching its own planted line first.
    for (const { name, pattern, planted } of FORBIDDEN) {
      expect(pattern.test(planted), name).toBe(true)
    }
    // And they do not fire on the ordinary code in this directory, or the suite would be asserting
    // something it could never satisfy.
    expect(FORBIDDEN.some((f) => f.pattern.test('const url = URL.createObjectURL(file)'))).toBe(false)
  })

  it('no file in this directory touches storage, the network, the clipboard or the console', () => {
    const offenders: string[] = []
    for (const file of source.keys()) {
      const code_ = codeOf(file)
      for (const { name, pattern } of FORBIDDEN) {
        if (pattern.test(code_)) offenders.push(`${file}: ${name}`)
      }
    }
    expect(offenders).toEqual([])
  })

  it('reads a paste from the paste event and never from the system clipboard', () => {
    // The one clipboard-adjacent thing here, and the distinction matters: `event.clipboardData` is
    // the payload of a paste the person just performed, whereas `navigator.clipboard` reads the
    // machine's clipboard whenever it likes. There is deliberately no copy button anywhere on this
    // surface — see CodeSheet's header note.
    const entry = codeOf('GroupEntry.svelte')
    expect(entry).toContain('event.clipboardData')
    expect(entry).not.toContain('navigator.clipboard')
    for (const file of componentFiles) {
      expect(proseOf(file), file).not.toMatch(/copy to clipboard|copy code/i)
    }
  })

  it('hands out a file through a blob URL, which reaches no server', () => {
    // Downloading is the one way bytes deliberately leave the page, and the person asked for it.
    // The pattern is the one App.svelte already uses to export a built tool: a blob URL and a
    // synthetic click. `connect-src 'self'` would refuse an upload, and nothing here attempts one.
    const flow = codeOf('NewCodeFlow.svelte')
    expect(flow).toContain('URL.createObjectURL')
    expect(flow).toContain('URL.revokeObjectURL')
    expect(copy.DOWNLOAD_IS_A_PLAINTEXT_COPY).toContain('plain, readable copy')
    expect(flow).toContain('DOWNLOAD_IS_A_PLAINTEXT_COPY')
  })

  it('wipes the open data key, and the identity it does not use, the moment each has been used', () => {
    // The open key is the one genuinely secret buffer a flow holds. zeroizeDataKey() promises only
    // that the buffer it was handed is overwritten, which is why it is called at the earliest point
    // rather than at the end of the session. "Get a code" never holds a key at all; the identity a
    // set-up hands back is wiped as it arrives, because this screen opens nothing with it.
    expect(codeOf('NewCodeFlow.svelte')).toContain('zeroizeOwnerIdentity(stored.identity)')
    const use = codeOf('UseCodeFlow.svelte')
    expect(use).toContain('zeroizeDataKey(dataKey)')
    expect(use).toContain('dataKey = null')
  })

  it('loads the crypto and the sync client on demand rather than into the offline viewer’s chunk', () => {
    // SyncPanel lazily loads the sync client so that a person opening a backup file never pays for
    // libsodium; this surface reaches the same library and must not undo that.
    const LAZY: Record<string, string[]> = {
      'NewCodeFlow.svelte': ["await import('../../recovery/serverKey')", "await import('../../sync/client')"],
      'UseCodeFlow.svelte': ["await import('../../recovery/dataKey')", "await import('../../recovery/serverKey')", "await import('../../sync/client')"],
      'KeySetup.svelte': ["await import('../../recovery/serverKey')"],
    }
    // A value import of any of these would put it in the chunk regardless. `import type` is erased
    // at build time and is what these files use.
    const EAGER = /^\s*import \{[^}]*\} from '\.\.\/\.\.\/(recovery\/dataKey|recovery\/serverKey|sync\/client|owner\/identity)'/m
    expect(EAGER.test("  import { setUp } from '../../recovery/serverKey'")).toBe(true)
    for (const [file, imports] of Object.entries(LAZY)) {
      const code_ = codeOf(file)
      for (const lazy of imports) expect(code_, `${file}: ${lazy}`).toContain(lazy)
      expect(code_, file).not.toMatch(EAGER)
    }
    expect(syncPanel).toContain("await import('./recovery/RecoveryPanel.svelte')")
    expect(syncPanel).not.toMatch(/^\s*import RecoveryPanel from/m)
  })

  it('has no file input anywhere, and saves no key: the key file is retired (#258)', () => {
    // The stand-in key file is gone outright, with no fallback and no import. The one thing these
    // screens still hand out as a file is the code itself, as text, which is a download and not an
    // input. The detector is shown catching the input it replaced.
    const FILE_INPUT = /<input[^>]*type=["']?file\b|\.files\??\.\[0\]|\bFileReader\b|\bfile\.text\(\)/
    expect(FILE_INPUT.test('<input type="file" accept="application/json,.json" onchange={loadFile} />')).toBe(true)
    expect(FILE_INPUT.test('const file = input.files?.[0]')).toBe(true)
    const offenders = componentFiles.filter((f) => FILE_INPUT.test(codeOf(f)))
    expect(offenders).toEqual([])
    const KEY_FILE = /wrapped-key-stand-in|encodeWrappedKeyFile|decodeWrappedKeyFile|Save the wrapped key|key file/i
    expect(KEY_FILE.test("download(encodeWrappedKeyFile(blob), 'daymark-wrapped-key-stand-in.json')")).toBe(true)
    for (const file of source.keys()) expect(KEY_FILE.test(codeOf(file)), file).toBe(false)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (g) What a locked key can say about itself without being opened.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

/** A blob-shaped value. Not a real one — nothing here derives anything. */
const fakeBlob: RecoverableDataKey = {
  v: 1,
  slots: [
    { kind: 'passphrase', kdf: { alg: 'argon2id', memMiB: 256, ops: 3 }, saltB64: 'c2FsdA', nonceB64: 'bm9uY2U', ctB64: 'Y3Q' },
    { kind: 'recovery', kdf: { alg: 'argon2id', memMiB: 256, ops: 3 }, saltB64: 'c2FsdDI', nonceB64: 'bm9uY2Uy', ctB64: 'Y3Qy' },
  ],
}

describe('(g) the locked key, counted rather than described', () => {
  it('counts the copies of the key rather than asserting what the design says', () => {
    // A blob with two recovery slots is a thing the format allows — the slot list is a list
    // precisely so a person can hold more than one code. A screen that said "one" would be
    // describing the design instead of the data in front of it.
    expect(slotSummary(fakeBlob)).toEqual({ passphrase: 1, recovery: 1 })
    expect(slotSummary({ v: 1, slots: [fakeBlob.slots[1], fakeBlob.slots[1]] })).toEqual({
      passphrase: 0,
      recovery: 2,
    })
  })

  it('leaves every question about the key’s strength to the module that owns it', () => {
    // Deliberately NOT a second KDF floor check. dataKey.ts validates every slot of every blob on
    // every use, because a blob is untrusted input; a second floor here would be a second thing to
    // keep in step, and the failure mode of two floors drifting is that one of them becomes the
    // lower one.
    const weak = { v: 1 as const, slots: [{ ...fakeBlob.slots[0], kdf: { alg: 'argon2id' as const, memMiB: 8, ops: 1 } }] }
    expect(slotSummary(weak)).toEqual({ passphrase: 1, recovery: 0 })
    expect(codeOf('session.ts')).not.toContain('memMiB')
  })
})
