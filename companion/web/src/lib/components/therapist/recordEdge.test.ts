/*
 * THE EDGE OF THE CLIENT RECORD, ASSERTED OVER ITS SOURCE (#399).
 *
 * The Record page closes on a note saying what it does not hold. That note used to say a person's
 * journal and check-in notes "stay on their phone and are not in a share at all", which is false
 * whenever the person ticks "Include my own words": the share then carries both, and this portal
 * shows them. A clinician who believed the note could think they had never read someone's own
 * words, or that an entry they did read reached them by mistake.
 *
 * What is true, and pinned here: the page holds self-check runs only; answers to individual
 * questions have no slot in a share (assignments/share.ts keeps a self-check's score and band); a
 * person's own words reach the clinician only when they chose to include them, and are shown on
 * other tabs, whose names the note uses and which must therefore exist.
 *
 * There is no DOM here, so the note is read off the `.svelte` source as the words a person reads.
 * Every absence is asserted after a control showing the same search finds the thing when present.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const read = (rel: string) => readFileSync(resolve(process.cwd(), rel), 'utf8')

const RECORD = read('src/lib/components/therapist/ClientRecordScreen.svelte')
const PORTAL = read('src/lib/components/therapist/TherapistPortal.svelte')

/** The words a person reads: script, style, comments, tags and Svelte blocks gone, spaces collapsed. */
const proseOf = (src: string) =>
  src
    .replace(/<script[\s\S]*?<\/script>/g, '')
    .replace(/<style[\s\S]*?<\/style>/g, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\{[#:/@][^}]*\}/g, ' ')
    .replace(/<[^>]*>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()

/** The prose of the closing card alone, from its opening tag to its closing one. */
function edgeOf(src: string): string {
  const open = src.indexOf('<Card title="The edge of this record"')
  const close = src.indexOf('</Card>', open)
  if (open < 0 || close < 0) return ''
  return proseOf(src.slice(open, close))
}

const EDGE = edgeOf(RECORD)

const ANSWERS =
  'It does not hold their answers to any individual question: a self-check is shared as its ' +
  'score and band, and its answers are not in a share at all.'
const TOTALS = 'It does not hold their totals either, which are in the file and are left undrawn on purpose.'
const OWN_WORDS =
  'It does not hold anything they wrote in their journal or in a check-in note. That reaches you ' +
  'only if they chose to include their own words when they made the share, and then it is shown ' +
  'elsewhere: their journal under Shared data, each note with its check-in in the Calendar.'

/** The sentence #399 took out, as the markup held it. */
const OLD =
  "It does not hold the person's journal or anything written in it, their check-in notes, or their " +
  'answers to any individual question — those stay on their phone and are not in a share at all.'
const OLD_CLAIM = /stay on their phone and are not in a share at all/

describe('the edge of the client record says only true things about a share (#399)', () => {
  it('has a subject: the closing card is found, and it is the one about this page', () => {
    expect(EDGE.length).toBeGreaterThan(400)
    expect(EDGE).toContain('This page holds self-check runs and nothing else')
  })

  it('says a self-check is shared as its score and band, and its answers never are', () => {
    expect(EDGE).toContain(ANSWERS)
    expect(EDGE).toContain(TOTALS)
  })

  it('says a person’s own words reach the clinician only by their choice, and where they are shown', () => {
    expect(EDGE).toContain(OWN_WORDS)
  })

  it('no longer says the journal and notes are never in a share', () => {
    // Control: planted back into the card, the old sentence is seen by the same reading.
    const planted = RECORD.replace(
      '<Card title="The edge of this record" tone="quiet">',
      `<Card title="The edge of this record" tone="quiet"><p class="para">${OLD}</p>`,
    )
    expect(planted).not.toBe(RECORD)
    expect(edgeOf(planted)).toMatch(OLD_CLAIM)
    expect(edgeOf(planted)).toMatch(/journal[^.]*not in a share/i)
    // The real card no longer makes the claim, in that sentence or any other.
    expect(EDGE).not.toMatch(OLD_CLAIM)
    expect(EDGE).not.toMatch(/journal[^.]*not in a share/i)
  })

  it('names only tabs the clinician can actually open', () => {
    // The note sends the reader to two other tabs by name, so a renamed tab would make it false.
    // One line per tab button; its handler holds an arrow, so `[^>]*` cannot span its attributes.
    const tab = (label: string) => new RegExp(`<button\\b[^\\n]*>${label}</button>`)
    // Control: the pattern finds a tab the portal has, and misses one it has not.
    expect(PORTAL).toMatch(tab('Record'))
    expect(PORTAL).not.toMatch(tab('Journal notes'))
    expect(PORTAL).toMatch(tab('Shared data'))
    expect(PORTAL).toMatch(tab('Calendar'))
  })
})
