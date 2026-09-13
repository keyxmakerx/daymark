/*
 * THE STANDING SHARING NOTICE (issue #105).
 *
 * Two things are worth asserting and neither is visual. First, what "active" and "since" are
 * computed from, because a strip that is always on screen is the worst place in the product to
 * print a wrong date or a wrong state. Second, the tokens — clay is this product's only alarm hue
 * and an ongoing consented share is not an alarm, so the one place clay may appear in this feature
 * is the confirm button that actually ends the share. That is a property of the stylesheet, and a
 * grep over the source is the direct test for it.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import {
  ENDED_LABEL,
  KEEP_SHARING,
  REVOKE_ACTION,
  SHARING_LABEL,
  UNNAMED_RECIPIENT,
  copiesNotRemoved,
  endedLine,
  revokeConsequence,
  revokeTitle,
  shareRefusedBecauseEnded,
  sharingLine,
  sharingStateFrom,
} from './sharing'
import { REVOKE_CAVEAT } from '../pairing/copy'

const meta = (version: number, createdAt: number) => ({ version, size: 10, contentHash: 'h', createdAt })

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (a) What the strip believes.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(a) active, and since when', () => {
  it('nothing published is not sharing', () => {
    expect(sharingStateFrom([])).toEqual({ active: false, since: null })
  })

  it('takes the earliest version, not the latest, whatever order they arrive in', () => {
    // "Since" is when sharing began. Taking the newest would restate today's date every time the
    // owner published an update, which reads as the share being new each time they look at it.
    const out = sharingStateFrom([meta(2, 3_000), meta(0, 1_000), meta(1, 2_000)])
    expect(out).toEqual({ active: true, since: 1_000 })
  })

  it('ignores a missing or zero timestamp rather than printing 1970', () => {
    // The one strip that is always on screen is the worst place to render an epoch date.
    expect(sharingStateFrom([meta(0, 0), meta(1, 5_000)])).toEqual({ active: true, since: 5_000 })
    expect(sharingStateFrom([meta(0, Number.NaN)])).toEqual({ active: true, since: null })
    expect(sharingStateFrom([{ version: 0, size: 1, contentHash: 'h' } as never])).toEqual({
      active: true,
      since: null,
    })
  })

  it('a revoked lineage reads as not sharing, not as previously sharing', () => {
    // Revoking marks every version and removes the bytes, so a withdrawn share comes back as an
    // empty list. The strip must not distinguish it: "previously sharing" would be a permanent
    // reminder of an ended relationship on every screen.
    expect(sharingStateFrom([]).active).toBe(false)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (b) The words.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(b) what it says', () => {
  it('names the person and the day sharing began', () => {
    expect(sharingLine('Dr Okafor', '3 March')).toBe('Sharing real entries with Dr Okafor since 3 March.')
  })

  it('drops the date rather than guessing when there is none', () => {
    expect(sharingLine('Dr Okafor', null)).toBe('Sharing real entries with Dr Okafor.')
  })

  it('never renders an empty gap where a name should be', () => {
    for (const blank of ['', '   ']) {
      expect(sharingLine(blank, null)).toContain(UNNAMED_RECIPIENT)
      expect(revokeTitle(blank)).toContain(UNNAMED_RECIPIENT)
      expect(revokeConsequence(blank)).toContain(UNNAMED_RECIPIENT)
    }
  })

  it('says "real entries", because that is the fact worth reminding somebody of', () => {
    // Not "your data" and not "a summary". What is shared is what they wrote.
    expect(sharingLine('X', null)).toContain('real entries')
  })

  it('offers keeping the share rather than cancelling a dialog', () => {
    // "Cancel" names the dialog. This names what not confirming actually does.
    expect(KEEP_SHARING).toBe('Keep sharing')
    expect(KEEP_SHARING.toLowerCase()).not.toContain('cancel')
    expect(REVOKE_ACTION).toBe('Revoke')
    // The repo's word is revoke, everywhere. "Stop sharing" was considered and rejected.
    expect(REVOKE_ACTION.toLowerCase()).not.toContain('stop')
  })

  it('says plainly when copies could not be removed, with the count', () => {
    // "Withdrawn" over copies still sitting on a disk would be telling somebody a thing is gone
    // when it is not. Singular and plural both read, since one leftover copy is the likely case.
    expect(copiesNotRemoved(1)).toContain('one copy')
    expect(copiesNotRemoved(1)).toContain('it is still on its disk')
    expect(copiesNotRemoved(3)).toContain('3 copies')
    expect(copiesNotRemoved(3)).toContain('they are still on its disk')
    expect(copiesNotRemoved(2)).not.toMatch(/success|removed everything|all clear/i)
  })

  it('the label is a noun for the state, not an instruction', () => {
    expect(SHARING_LABEL).toBe('SHARING')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (c) The strip, over its source: one alarm, and the caveat at the point of the click.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(c) the strip', () => {
  const source = readFileSync(
    fileURLToPath(new URL('../components/owner/SharingStrip.svelte', import.meta.url)),
    'utf8',
  )
  const code = source.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '')

  it('is chrome with an indigo rule, never a clay ground', () => {
    expect(code).toContain('background: var(--chrome)')
    expect(code).toContain('border-left: 3px solid var(--indigo)')
    // The detector, shown catching what it is for.
    expect('.strip { background: var(--clay); }').toMatch(/\.strip\s*\{[^}]*var\(--clay\)/)
    expect(code).not.toMatch(/\.strip\s*\{[^}]*var\(--clay\)/)
  })

  it('spends clay exactly once, on the button that ends the share', () => {
    const clay = [...code.matchAll(/var\(--clay\)/g)]
    expect(clay.length).toBeGreaterThan(0)
    // Every occurrence is inside the .destructive rule and nowhere else.
    const destructive = code.slice(code.indexOf('.destructive'), code.indexOf('button:disabled'))
    expect([...destructive.matchAll(/var\(--clay\)/g)].length).toBe(clay.length)
  })

  it('puts the caveat directly above the buttons, which is the point of the click', () => {
    const caveat = code.indexOf('{REVOKE_CAVEAT}')
    const consequence = code.indexOf('{revokeConsequence(name)}')
    const actions = code.indexOf('class="actions"')
    expect(caveat).toBeGreaterThan(-1)
    expect(caveat).toBeLessThan(consequence)
    expect(consequence).toBeLessThan(actions)
    // The sentence itself is the shared constant, never retyped here.
    expect(code).not.toContain('does not un-send')
    expect(REVOKE_CAVEAT).toContain('does not un-send what was already read')
  })

  it('cannot be dismissed', () => {
    // Dismissal is forgetting: a share is still live after somebody closes a notice about it.
    const DISMISSAL = [
      { name: 'a dismiss handler', pattern: /\bdismiss/i, planted: 'onclick={dismiss}' },
      { name: 'a hide toggle', pattern: /\bhidden\b|\bhide\b/i, planted: 'let hidden = $state(false)' },
      { name: 'a never-again opt-out', pattern: /don.t show|never show/i, planted: "<label>Don't show this again</label>" },
    ]
    /*
     * `aria-hidden` is removed before the check. It is on the decorative ring glyph, it is the
     * opposite of a dismissal — it hides a mark from assistive tech precisely so the WORDS carry
     * the meaning — and leaving it in would have made the hide detector fire on the one attribute
     * that proves the strip is doing this right.
     */
    const withoutAria = code.replace(/aria-hidden/g, '')
    for (const { name, pattern, planted } of DISMISSAL) {
      expect(pattern.test(planted), name).toBe(true)
      expect(pattern.test(withoutAria), name).toBe(false)
    }
  })

  it('does not read a failed check as "not sharing"', () => {
    // An unreachable server says nothing about whether somebody has access, and a strip that
    // vanished on a timeout would be the one element whose absence reads as reassurance.
    expect(code).toContain('could not check whether sharing is active')
    expect(code).not.toMatch(/catch[\s\S]{0,60}versions = \[\]/)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (d) When the clinician ended it themselves (issue #91).
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(d) the ended state', () => {
  const stripSource = readFileSync(
    fileURLToPath(new URL('../components/owner/SharingStrip.svelte', import.meta.url)),
    'utf8',
  )
  const stripCode = stripSource.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '')

  it('the label is a noun for the state, like the live one', () => {
    expect(ENDED_LABEL).toBe('ACCESS ENDED')
    // Not an instruction, not an accusation, not a verb aimed at the reader.
    expect(ENDED_LABEL).not.toMatch(/you|check|warning|action/i)
  })

  it('says what and when, and names no reason it cannot know', () => {
    const line = endedLine('Dr Okafor', '3 March 2026')
    expect(line).toContain('Dr Okafor ended their access on 3 March 2026')
    expect(line).toContain('Nothing you send now would be read')
    // The server knows the relationship ended and nothing whatever about why. A console that
    // guessed would be narrating somebody's professional life to a patient.
    const NARRATION = /left the practice|retired|no longer works|was removed|dismissed/i
    expect(NARRATION.test('Dr Okafor left the practice.')).toBe(true)
    expect(NARRATION.test(line)).toBe(false)
  })

  it('drops the date rather than guessing when there is not one', () => {
    expect(endedLine('Dr Okafor', null)).toBe(
      'Dr Okafor ended their access. Nothing you send now would be read.',
    )
    expect(endedLine('  ', null)).toContain(UNNAMED_RECIPIENT)
  })

  it('reads as a fact, never as a failure or an alarm', () => {
    const both = endedLine('Dr Okafor', '3 March 2026') + ' ' + ENDED_LABEL
    const ALARMING = /error|problem|lost|failed|warning|urgent/i
    expect(ALARMING.test('Error: sharing failed.')).toBe(true)
    expect(ALARMING.test(both)).toBe(false)
  })

  it('the refusal at the point of sharing names the consequence and the two ways out', () => {
    const r = shareRefusedBecauseEnded('Dr Okafor', '3 March 2026')
    // Literally true, and it has to be: the check runs before anything is sealed.
    expect(r).toContain('Nothing was sealed or sent')
    expect(r).toContain('ended their access on 3 March 2026')
    expect(r).toContain('nothing sent now would be read')
    expect(r).toContain('revoke what is still published')
    expect(r).toContain('invite them again')
    // No cause. The server knows the ending, not the reason.
    expect(r).not.toMatch(/because they|since they left|due to/i)
  })

  it('the strip replaces the sharing line rather than sitting beside it', () => {
    // Both cannot be true at once. A strip that still said somebody had standing access would be
    // the one permanently visible element whose words read as reassurance that notes were landing.
    const ended = stripCode.indexOf('{#if loaded && endedAt}')
    const live = stripCode.indexOf('{:else if loaded && sharing.active}')
    expect(ended).toBeGreaterThan(-1)
    // The ended branch comes FIRST and the live one is its else, so they can never both render.
    expect(live).toBeGreaterThan(ended)
    expect(stripCode.slice(ended, live)).not.toContain('{sharingLine(')
    // Control: the same search finds the live sentence in the branch that does carry it.
    expect(stripCode.slice(live)).toContain('{sharingLine(')
  })

  it('the ended strip is chrome, and clay is still spent only on the confirm', () => {
    // One confirm, shared by both strips as a snippet — so the caveat cannot drift into two
    // almost-identical copies, which is how a verbatim requirement quietly stops being verbatim.
    expect(stripCode.match(/\{@render confirmRevoke\(\)\}/g)).toHaveLength(2)
    expect(stripCode.match(/\{REVOKE_CAVEAT\}/g)).toHaveLength(1)
    expect(stripCode.match(/class="destructive"/g)).toHaveLength(1)
  })

  it('does not read a failed ending check as "they left"', () => {
    // An unreachable server says nothing about whether the clinician ended anything, and a console
    // that answered "they left" on a timeout would stop somebody sharing with a therapist who is
    // still there. The strip falls back to the live state; the seal falls through to the server.
    expect(stripSource).toContain('endedAt = null')
    const builder = readFileSync(
      fileURLToPath(new URL('../components/owner/ShareBuilder.svelte', import.meta.url)),
      'utf8',
    )
    expect(builder).toContain('.catch(() => null)')
    expect(builder).toContain('shareRefusedBecauseEnded')
  })
})
