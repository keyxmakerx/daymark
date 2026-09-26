/*
 * The portal's wiring, asserted over its source.
 *
 * These are structural checks on markup, which is a weaker instrument than a behavioural test —
 * but the alternative here is no check at all: the properties below live in how four components
 * are composed, not in any function, and the repo has no component-rendering harness. They are
 * written so each one fails if the composition is undone.
 *
 * Every absence assertion is paired with a control proving the same search finds the thing when
 * it IS present. This repo has shipped several checks that asserted nothing, most recently a
 * fixture that built an epoch-day field as milliseconds and so hid a bug that put every sleep log
 * on 1 January 1970 while thirty-eight tests passed.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const read = (rel: string) => readFileSync(resolve(process.cwd(), rel), 'utf8')

const PORTAL = read('src/lib/components/therapist/TherapistPortal.svelte')
const ACCEPTANCE = read('src/lib/components/therapist/InviteAcceptance.svelte')
const GATE = read('src/lib/components/therapist/LoginGate.svelte')
const SHARED_VIEW = read('src/lib/components/therapist/SharedDataView.svelte')
const SCREEN = read('src/lib/components/therapist/SignInScreen.svelte')
const OWNER_APP = read('src/App.svelte')

/** Source with commentary removed: what actually ships. Same shape as the tree-wide suite's. */
const codeOnly = (src: string) =>
  src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(?<!:)\/\/[^\n]*/g, '')

/** The markup a person actually gets: script and style gone, comments gone. */
const markupOf = (src: string) =>
  src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<script[\s\S]*?<\/script>/g, '')
    .replace(/<style[\s\S]*?<\/style>/g, '')

const GATE_MARKUP = markupOf(GATE)
const SCREEN_MARKUP = markupOf(SCREEN)

describe('the portal composes the sign-in contract around the one auth path', () => {
  it('renders LoginGate inside SignInScreen rather than beside or instead of it', () => {
    expect(PORTAL).toContain('<SignInScreen>')
    expect(PORTAL).toContain('{#snippet credentials()}')
    const snippet = PORTAL.slice(PORTAL.indexOf('{#snippet credentials()}'))
    expect(snippet.slice(0, snippet.indexOf('{/snippet}'))).toContain('<LoginGate')
  })

  it('suppresses the gate’s own chrome, so the fixed notice appears exactly once', () => {
    expect(PORTAL).toContain('standalone={false}')
    // The control: the prop genuinely gates both pieces of chrome in the gate itself, so passing
    // it false is doing something. Without this, the assertion above is satisfied by a prop that
    // no longer exists on the component.
    expect(GATE).toContain('{#if standalone}<LowerAssuranceBanner />{/if}')
    expect(GATE).toMatch(/\{#if standalone\}<h2>[^<]*<\/h2>\{\/if\}/)
  })

  it('defaults to standalone, so mounting the gate on its own is still safe', () => {
    expect(GATE).toContain('standalone = true')
  })
})

describe('one bundle, opened deliberately, cleared with the session', () => {
  it('holds the opened share on the portal rather than in a module-level store', () => {
    // Module-level state would outlive the component that owns the session, which for decrypted
    // personal data is the difference between a lifetime and a leak.
    expect(PORTAL).toContain('let shared = $state<BackupData | null>(null)')
    expect(PORTAL).not.toMatch(/^export (const|let) shared/m)
  })

  it('clears the bundle on logout', () => {
    const logout = PORTAL.slice(PORTAL.indexOf('function logout()'))
    const body = logout.slice(0, logout.indexOf('\n  }'))
    expect(body).toContain('shared = null')
    // The control: this slicer really does capture the logout body, proved by a line that is
    // unambiguously inside it. A slicer that captured nothing would satisfy `not.toContain` above
    // and would have satisfied this too if it only asserted absence.
    expect(body).toContain('ctx = null')
  })

  it('a refused share clears the sibling surfaces instead of leaving them drawn', () => {
    // The failure path matters more than the success path here: a bundle that has just been
    // rejected as unverifiable must not go on being rendered on Today, Calendar or Record.
    expect(SHARED_VIEW).toContain('onopen?.(null)')
    expect(SHARED_VIEW).toContain('onopen?.(data)')
  })

  it('does not fetch the share as a side effect of changing tabs', () => {
    // Opening someone's records is a deliberate act, not something navigation does quietly.
    const tabHandlers = PORTAL.match(/onclick=\{\(\) => \(tab = '[a-z]+'\)\}/g) ?? []
    expect(tabHandlers.length).toBeGreaterThan(3) // the tabs really are wired this way
    for (const h of tabHandlers) expect(h).not.toContain('fetch')
    expect(PORTAL).not.toContain('fetchShare')
  })
})

describe('the grant the portal trusts is the one written for this clinician', () => {
  it('checks the grant against the signing key this portal holds', () => {
    expect(codeOnly(PORTAL)).toContain('verifyGrantBlob(current.bytes, c.pinnedOwnerSignPub, c.therapistFp)')
  })

  it('does not report another clinician\'s grant as a failed signature', () => {
    // Both refusals show nothing, but they send the clinician to different places: a failed
    // signature is not theirs to fix, and a grant for another key is the owner's to re-publish.
    expect(codeOnly(PORTAL)).toContain('e instanceof GrantAddressError')
  })
})

describe('an older copy of a share is refused in words of its own', () => {
  it('names it apart from a failed signature, in the same voice as the other closed shares', () => {
    const code = codeOnly(SHARED_VIEW)
    expect(code).toContain('e instanceof ShareOlderError')
    expect(code).toContain('This copy was sealed before one you have already opened, so it stays closed. Ask for a fresh one.')
  })
})

describe('the dated screens are given a ticking clock, not left to read one', () => {
  it('passes `now` as a value and ticks it in an $effect', () => {
    // `$derived` tracks only what it reads; a Date.now() inside a callee is invisible to it. That
    // bug shipped in this very file's idle guard and had to be fixed the same way.
    expect(PORTAL).toContain('let now = $state(Date.now())')
    expect(PORTAL).toContain('now = Date.now()')
    expect(PORTAL).toContain('clearInterval')
    for (const screen of ['<TodayScreen', '<CalendarScreen', '<ClientRecordScreen']) {
      const i = PORTAL.indexOf(screen)
      expect(i).toBeGreaterThan(-1)
      expect(PORTAL.slice(i, PORTAL.indexOf('/>', i))).toContain('{now}')
    }
  })
})

describe('the data surfaces sit behind the capability that feeds them', () => {
  it('gates Today, Calendar and Record on read.share, like the share itself', () => {
    const i = PORTAL.indexOf("hasCapability(grant, 'read.share')")
    expect(i).toBeGreaterThan(-1)
    const block = PORTAL.slice(i, PORTAL.indexOf('</nav>', i))
    for (const tab of ["tab = 'today'", "tab = 'calendar'", "tab = 'record'", "tab = 'shared'"]) {
      expect(block).toContain(tab)
    }
    // The control: a tab that is NOT behind this capability is outside the block, so the four
    // above are inside it because of the gate rather than because the block is the whole file.
    expect(block).not.toContain("tab = 'allowed'")
  })
})

describe('the gate asks for what is yours first, and offers the rest', () => {
  it('draws the authenticator code and the reading passphrase before the fallback disclosure', () => {
    const totp = GATE_MARKUP.indexOf('id="f-totpCode"')
    const passphrase = GATE_MARKUP.indexOf('id="f-readingPassphrase"')
    const details = GATE_MARKUP.indexOf('<details')
    expect(totp).toBeGreaterThan(-1)
    expect(passphrase).toBeGreaterThan(totp)
    expect(details).toBeGreaterThan(passphrase)
    // The control: the disclosure really does hold the fallback fields, so "before it" means
    // before nine fields rather than before an empty element.
    const inside = GATE_MARKUP.slice(details, GATE_MARKUP.indexOf('</details>', details))
    expect(inside).toContain('id="f-serverUrl"')
    expect(inside).toContain('id="f-wrappedKey"')
  })

  it('folds the fallback by default and binds it to state, so a check can open it', () => {
    expect(GATE_MARKUP).toContain('<details class="prov" bind:open={provOpen}>')
    expect(GATE_MARKUP).not.toContain('<details class="prov" open>')
    expect(codeOnly(GATE)).toContain('let provOpen = $state(false)')
  })

  it('the summary reads as an offer, and the explanation stays inside', () => {
    const details = GATE_MARKUP.indexOf('<details')
    const inside = GATE_MARKUP.slice(details, GATE_MARKUP.indexOf('</details>', details))
    const summary = inside.slice(inside.indexOf('<summary>'), inside.indexOf('</summary>'))
    expect(summary).toContain('This browser has no record of your invitation?')
    expect(summary).toContain('Enter the connection by hand')
    expect(inside).toContain('so these have to be entered by hand')
    expect(inside).toContain('Accepting the invitation in the browser you sign in from is the shorter path.')
  })

  it('judges the form before it unwraps or sends anything', () => {
    const code = codeOnly(GATE)
    const unlock = code.slice(code.indexOf('async function unlockNow()'))
    const check = unlock.indexOf('firstProblem(')
    const crypto = unlock.indexOf('initAssignmentCrypto()')
    const network = unlock.indexOf('loginTotp(')
    expect(check).toBeGreaterThan(-1)
    expect(crypto).toBeGreaterThan(check)
    expect(network).toBeGreaterThan(crypto)
    // A found problem returns before `busy` is set, so the button never shows "Unlocking…" for a
    // form that was never sent.
    const found = unlock.slice(unlock.indexOf('if (problem)'), unlock.indexOf('busy = true'))
    expect(found).toContain('return')
    expect(found).toContain('provOpen = true')
    expect(found).toContain('.focus()')
  })

  it('renders the alert above the button, inside the form, and points the failing field at it', () => {
    const form = GATE_MARKUP.slice(GATE_MARKUP.indexOf('<form'), GATE_MARKUP.indexOf('</form>'))
    const alert = form.indexOf('<Callout tone="critical">')
    const button = form.indexOf('type="submit"')
    expect(alert).toBeGreaterThan(-1)
    expect(button).toBeGreaterThan(alert)
    // The failing field is marked and described by the alert, so the sentence is also heard
    // where the cursor is.
    expect(form).toMatch(/id="f-totpCode"[^>]*aria-invalid=/)
    expect(form).toMatch(/id="f-totpCode"[^>]*aria-describedby=\{describedBy\('totpCode'\)\}/)
    expect(form).toContain('<div id={errorId}><Callout tone="critical">')
  })
})

describe('the sign-in screen sits where the other surfaces sit', () => {
  it('centres at the owner viewer’s measure', () => {
    const rule = SCREEN.slice(SCREEN.indexOf('.signin {'), SCREEN.indexOf('}', SCREEN.indexOf('.signin {')))
    expect(rule).toContain('max-width: var(--maxw)')
    expect(rule).toContain('margin: 0 auto')
    // The control: the same two declarations are what centre the owner viewer's shell.
    const shell = OWNER_APP.slice(OWNER_APP.indexOf('.shell {'), OWNER_APP.indexOf('}', OWNER_APP.indexOf('.shell {')))
    expect(shell).toContain('max-width: var(--maxw)')
    expect(shell).toContain('margin: 0 auto')
  })

  it('opens with the wordmark and its tagline, in the owner viewer’s brand markup, above the title', () => {
    const brand = SCREEN_MARKUP.indexOf('<div class="brand">')
    const title = SCREEN_MARKUP.indexOf('<PageHeader')
    expect(brand).toBeGreaterThan(-1)
    expect(title).toBeGreaterThan(brand)
    const block = SCREEN_MARKUP.slice(brand, title)
    expect(block).toContain('<span class="mark" aria-hidden="true"></span>')
    expect(block).toContain('Daymark Companion')
    // The page is named for who uses it, with the same noun as the other three (#158, #310).
    expect(block).toContain('<p class="muted tagline">Clinician console</p>')
    expect(block).not.toContain('Therapist portal')
    // Control: the retired name planted back into the real masthead is seen.
    expect(block.replace('Clinician console', 'Therapist portal')).toContain('Therapist portal')
    // The pattern is the owner viewer's, read from its source rather than retyped.
    expect(OWNER_APP).toContain('<div class="brand">')
    expect(OWNER_APP).toContain('<span class="mark" aria-hidden="true"></span>')
    expect(OWNER_APP).toContain('class="muted tagline"')
    // Not a second <h1>: PageHeader owns this document's top heading.
    expect(block).not.toContain('<h1')
  })

  it('on a narrow window draws the credential column first, without moving it in the document', () => {
    const contract = SCREEN_MARKUP.indexOf('class="col contract"')
    const credentials = SCREEN_MARKUP.indexOf('class="col credentials"')
    expect(contract).toBeGreaterThan(-1)
    expect(credentials).toBeGreaterThan(contract)
    const narrow = SCREEN.indexOf('@media (max-width: 45rem)')
    expect(narrow).toBeGreaterThan(-1)
    const rule = SCREEN.slice(narrow, SCREEN.indexOf('}\n  }', narrow))
    expect(rule).toContain('.credentials')
    expect(rule).toContain('order: -1')
  })

  it('does not fold the contract to do it', () => {
    expect(SCREEN_MARKUP).not.toContain('<details')
    // The control: the same search finds the disclosure the gate does have.
    expect(GATE_MARKUP).toContain('<details')
  })
})

describe('a paused connection is not drawn as a failure', () => {
  /*
   * The acceptance screen has one place it puts refusals, and until the pairing budgets were split
   * everything that reached it was a fault: a dead link, a wrap that would not reopen, an
   * enrolment the server refused. A CONNECTION PAUSE is not one of those. The invitation is alive,
   * nothing has been spent, and the only thing to do is come back in a few minutes — so it must
   * not arrive in the alarm hue, which would contradict the words inside it.
   *
   * Structural, because the property lives in the markup: the callout's tone is chosen from which
   * step refused.
   */
  it('chooses the tone from the step, so the paused message is not critical', () => {
    const markup = markupOf(ACCEPTANCE)
    expect(markup).toContain("errorStep === 'paused' ? 'info' : 'critical'")
    // The control: the search would see a hard-coded critical tone if one came back, and there is
    // exactly one error callout to find.
    expect(markup).not.toMatch(/\{#if error\}<Callout tone="critical"/)
    expect((markup.match(/\{#if error\}<Callout/g) ?? []).length).toBe(1)
  })

  it('the step that names it is set from the refusal rather than guessed', () => {
    const code = codeOnly(ACCEPTANCE)
    expect(code).toContain('errorStep = e instanceof AcceptError ? e.step : null')
    // And it is cleared when a flow starts, so one screen's pause cannot colour the next
    // screen's failure. Three handlers clear the message; all three must clear the step with it.
    const cleared = (code.match(/error = ''\n\s*errorStep = null/g) ?? []).length
    expect(cleared).toBe((code.match(/\berror = ''/g) ?? []).length)
    expect(cleared).toBeGreaterThan(0)
  })
})
