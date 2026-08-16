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
const GATE = read('src/lib/components/therapist/LoginGate.svelte')
const SHARED_VIEW = read('src/lib/components/therapist/SharedDataView.svelte')

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
