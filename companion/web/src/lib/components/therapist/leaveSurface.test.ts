/*
 * THE LEAVE CONTROL, ASSERTED OVER ITS SOURCE (issue #91).
 *
 * The sequencing is a node test in therapist/leave.ts. What is left here lives in markup —
 * where the control sits, what the confirm says and in what order, which element is clay — and
 * there is no DOM harness in this project (vite.config.ts sets `environment: 'node'`), so it is
 * read off the `.svelte` source in the shape the rest of the tree uses.
 *
 * Every absence assertion is paired with a control proving the same search finds the thing when it
 * IS present. A check that cannot see a planted example proves only that it is blind.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import {
  KEEP_ACCESS,
  LEAVE_ACTION,
  LEAVE_CONFIRM,
  LEAVE_TITLE,
} from '../../therapist/leave'

const read = (rel: string) => readFileSync(resolve(process.cwd(), rel), 'utf8')

const LEAVE = read('src/lib/components/therapist/LeaveRelationship.svelte')
const PORTAL = read('src/lib/components/therapist/TherapistPortal.svelte')

/** The markup a person actually gets: script, style and comments gone. */
const markupOf = (src: string) =>
  src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<script[\s\S]*?<\/script>/g, '')
    .replace(/<style[\s\S]*?<\/style>/g, '')

const LEAVE_MARKUP = markupOf(LEAVE)

/*
 * Source with commentary removed: what actually ships.
 *
 * Needed for every negative assertion below, and the reason is one this repo has already paid for.
 * The header of the component explains why it does NOT ask for a passphrase — so a `not.toMatch`
 * over the raw file duly found the word there and failed. A guard that cannot tell an explanation
 * from the thing it explains would push you to delete the explanation.
 */
const codeOnly = (src: string) =>
  src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(?<!:)\/\/[^\n]*/g, '')

const LEAVE_CODE = codeOnly(LEAVE)
const PORTAL_CODE = codeOnly(PORTAL)

describe('(a) where the control is', () => {
  it('sits at the foot of the Allowed tab, after the panel it follows', () => {
    const allowed = PORTAL.indexOf("{#if tab === 'allowed'}")
    const panel = PORTAL.indexOf('<AllowedPanel', allowed)
    const leave = PORTAL.indexOf('<LeaveRelationship', allowed)
    const nextTab = PORTAL.indexOf(":else if tab === 'assign'", allowed)
    expect(panel).toBeGreaterThan(-1)
    expect(leave).toBeGreaterThan(panel)
    expect(leave).toBeLessThan(nextTab)
  })

  it('is not in the topline beside Log out, which is the misclick this avoids', () => {
    const topline = PORTAL.slice(PORTAL.indexOf('<div class="topline">'), PORTAL.indexOf('</div>', PORTAL.indexOf('<div class="topline">')))
    expect(topline).toContain('onclick={logout}')
    expect(topline).not.toContain('LeaveRelationship')
    // Control: the same slice does contain a component when one is put in it.
    expect(`${topline}<LeaveRelationship />`).toContain('LeaveRelationship')
  })

  it('never renders behind a passphrase field, which would be theatre', () => {
    // The passphrase protects the key record, and the record is what is being given up. Asking for
    // it buys nothing: anybody at this screen is already through it.
    expect(LEAVE_CODE).not.toMatch(/type="password"|passphrase/i)
    // Control: the search fires on a field that does ask for one.
    expect('<input type="password" />').toMatch(/type="password"|passphrase/i)
  })
})

describe('(b) what the confirm says, and in what order', () => {
  const confirm = LEAVE_MARKUP.slice(LEAVE_MARKUP.indexOf('class="confirm"'), LEAVE_MARKUP.indexOf('</div>', LEAVE_MARKUP.indexOf('class="actions"')))

  it('states the four sentences as the shared constants, never reworded', () => {
    for (const name of [
      'LEAVE_IS_NOT_UNDONE',
      'LEAVE_TOUCHES_NOTHING_OF_THEIRS',
      'LEAVE_DOES_NOT_REACH_COPIES',
      'TELL_THEM_YOURSELF',
    ]) {
      expect(confirm, name).toContain(`{${name}}`)
    }
    // Control: a paragraph that says something close but different does not satisfy the same check.
    expect('<p>Leaving cannot be undone.</p>').not.toContain('{LEAVE_IS_NOT_UNDONE}')
  })

  it('leads with irreversibility, then the owner’s side, then copies, then the nudge', () => {
    // The order is the decision this surface was designed around: leading with "nothing of theirs
    // changes" answers a question no clinician was asking, at the cost of the one they need.
    const at = (n: string) => confirm.indexOf(`{${n}}`)
    expect(at('LEAVE_IS_NOT_UNDONE')).toBeLessThan(at('LEAVE_TOUCHES_NOTHING_OF_THEIRS'))
    expect(at('LEAVE_TOUCHES_NOTHING_OF_THEIRS')).toBeLessThan(at('LEAVE_DOES_NOT_REACH_COPIES'))
    expect(at('LEAVE_DOES_NOT_REACH_COPIES')).toBeLessThan(at('TELL_THEM_YOURSELF'))
  })

  it('offers Keep access and Leave, and names the keep in terms of what it does', () => {
    // Not "Cancel": cancel names the dialog, this names the consequence of shutting it.
    expect(KEEP_ACCESS).toBe('Keep access')
    expect(LEAVE_CONFIRM).toBe('Leave')
    expect(LEAVE_ACTION).toBe('Leave this relationship')
    expect(confirm).toContain('{KEEP_ACCESS}')
    expect(confirm).toContain('{LEAVE_CONFIRM}')
  })

  it('names nobody, because this console does not hold the person’s name', () => {
    // A heading with a patient's name on the clinician's screen would mean this product had grown
    // a route that carries one. It has not, and this is where that would first show up.
    expect(LEAVE_TITLE).toBe('Leave this relationship?')
    expect(LEAVE_CODE).not.toMatch(/displayName|therapist\.name|\bwho\b/)
    // Control: the search fires on a component that did take a name.
    expect('let { displayName } = $props()').toMatch(/displayName|therapist\.name|\bwho\b/)
  })
})

describe('(c) clay is spent once, on the act itself', () => {
  it('paints exactly one element clay, and it is the confirm button', () => {
    // Clay is the product's only alarm hue — needs a human, overdue, refused, destructive. A
    // clinician putting down access is none of the first three; the fourth is this one button.
    const style = LEAVE.slice(LEAVE.indexOf('<style>'))
    expect([...style.matchAll(/var\(--clay\)/g)]).toHaveLength(2) // background + border of one rule
    expect(style).toContain('.destructive { background: var(--clay)')
    expect(LEAVE_MARKUP.match(/class="destructive"/g)).toHaveLength(1)
  })

  it('the control that only opens the question is plain', () => {
    const opener = LEAVE_MARKUP.slice(0, LEAVE_MARKUP.indexOf('class="confirm"'))
    expect(opener).toContain('class="plain"')
    expect(opener).not.toContain('class="destructive"')
  })

  it('nothing here reads as success, failure or an alarm', () => {
    const FORBIDDEN = [
      { name: 'congratulation', pattern: /\b(success|done!|great|all set|✓)\b/i, planted: 'Success! ✓' },
      { name: 'alarm tone', pattern: /tone="critical"|tone="warn"/, planted: '<Callout tone="critical">' },
    ]
    for (const { name, pattern, planted } of FORBIDDEN) {
      expect(pattern.test(planted), name).toBe(true)
      expect(pattern.test(LEAVE_MARKUP), name).toBe(false)
    }
  })
})

describe('(d) the surface cannot reach past its three operations', () => {
  const code = LEAVE_CODE

  it('hands the module three ports and calls nothing else on the client', () => {
    // The module's own test proves it uses only what it is given. This proves what it is given: an
    // ending call, the browser's key storage, and a logout. Anything else the console can do —
    // reading a share, publishing an assignment — is not wired in here to be reached by mistake.
    const calls = [...code.matchAll(/ctx\.client\.(\w+)/g)].map((m) => m[1]).sort()
    expect(calls).toEqual(['leaveRelationship', 'logout'])
  })

  it('tears the session down only when the leave completed', () => {
    // A failed leave must not sign somebody out: they are still in the relationship, and landing on
    // a sign-in screen would say the opposite of what happened.
    const handler = code.slice(code.indexOf('async function leave()'), code.indexOf('</script>'))
    const afterOk = handler.slice(handler.indexOf('if (outcome.ok)'))
    expect(handler).toContain('if (outcome.ok)')
    // The only onleft() call is inside the ok branch, and the branch returns before any refusal
    // sentence is reached — so no failure path can fall through into tearing the session down.
    expect(afterOk.indexOf('onleft(')).toBeLessThan(afterOk.indexOf('refusal ='))
    expect(afterOk.slice(afterOk.indexOf('onleft('), afterOk.indexOf('refusal ='))).toContain('return')
    // Control: the same two checks fail on a handler that signs out first and asks afterwards.
    const planted = 'onleft(x)\n if (!outcome.ok) refusal = y'
    expect(planted.slice(planted.indexOf('onleft('), planted.indexOf('refusal ='))).not.toContain('return')
  })

  it('the portal keeps the closing sentence alive across the teardown', () => {
    // Dropping ctx unmounts the panel the sentence was rendered in, so it is carried out to the
    // screen the clinician actually lands on. Without this they confirm something irreversible and
    // meet a blank sign-in form.
    expect(PORTAL).toContain('onleft={onLeft}')
    expect(PORTAL).toContain('{leftNotice}')
    const signedOut = PORTAL.slice(PORTAL.indexOf('{#if !ctx}'), PORTAL.indexOf('<SignInScreen>'))
    expect(signedOut).toContain('{leftNotice}')
  })

  it('the leave path does not call logout() again, which would be a request that can only fail', () => {
    // The module already signed out, and the server deleted every session for that credential when
    // it recorded the ending. onLeft zeroizes and drops state; it does not re-POST.
    const onLeft = PORTAL_CODE.slice(PORTAL_CODE.indexOf('function onLeft'), PORTAL_CODE.indexOf('const canAssign'))
    expect(onLeft).toContain('zeroize(ctx.keys)')
    expect(onLeft).not.toContain('client.logout')
    // Control: the sibling that DOES sign out names it, so this search is looking at the right thing.
    expect(
      PORTAL_CODE.slice(PORTAL_CODE.indexOf('function logout()'), PORTAL_CODE.indexOf('function onLeft')),
    ).toContain('client.logout')
  })
})
