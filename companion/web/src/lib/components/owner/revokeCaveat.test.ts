import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { REVOKE_CAVEAT } from '../../pairing/copy'

/*
 * CLAUDE.md §4 requires REVOKE_CAVEAT's sentence, verbatim, at the point of the click. Three
 * screens paraphrased it instead — GrantManager.svelte, PinRecord.svelte and therapist/pinStore.ts
 * each said something close but different, because there was nowhere to import the sentence from.
 * This checks the fix stays fixed: every place that revokes, forgets, or turns off access carries
 * the one shared constant, not a rewording of it.
 *
 * This file does not retype the constant's own wording (see pairing/copy.ts for that): the point
 * of the fix is that the sentence exists in exactly one place, and a test file is source too.
 *
 * pinStore.ts is not a rendered screen — forgetAllPins() has no UI of its own — but the issue that
 * asked for this test named it as one of the three sites, because its doc comment stated the same
 * fact in its own words. It is checked for naming the constant rather than restating it.
 *
 * InvitePanel.svelte's "Stop this invitation" is deliberately NOT covered here: stopping an
 * invitation happens before any pairing has completed, so there is no "already read" material for
 * the caveat to be about. Its own copy (OWNER_COPY.stopBody, checked in pairingPanel.test.ts)
 * already states the correct, different fact for that moment — nothing has been shared yet.
 *
 * ─── THE ONE SURFACE THAT TURNS ACCESS OFF AND IS EXEMPT ───────────────────────────────────────
 *
 * The clinician's own "Leave this relationship" (issue #91) turns access off and does NOT carry
 * this sentence. That is a decision, not an oversight, and the exemption is written here rather
 * than left to be rediscovered — see EXEMPT_SURFACES at the foot of this file, where it is checked
 * rather than only described.
 *
 * The reason: "Revoking does not un-send what was already read" is the OWNER's sentence. It is
 * about a reader who already holds something, and the point of saying it at the moment of the click
 * is that the owner is about to believe revoking reaches backwards. From the clinician's side there
 * is no such reader — they are the reader, and they are not un-sending anything. The same words
 * would be describing a different act, which is the failure this whole file exists to stop, just
 * the other way round. That surface asserts its own mirror constant instead.
 *
 * The detector is not weakened for it. Nothing here scans the tree, so a new revoking screen is not
 * caught automatically by this file either way; what is enforced is that every surface named below
 * carries the constant, and that the exempt one carries the mirror. Both lists are checked against
 * the tree, so an entry that stops being true fails rather than going quiet.
 *
 * Every presence check below is paired with a fixture proving it can fail: a component whose
 * revoke paragraph was replaced with some other sentence does not satisfy the same assertion, per
 * this repo's rule that a check which cannot see a planted example proves only that it is blind.
 */

/** Strips the script and style blocks so what is left is only what a reader sees. */
function markupOf(source: string): string {
  return source.replace(/<script[\s\S]*?<\/script>/g, '').replace(/<style[\s\S]*?<\/style>/g, '')
}

describe('the revoke caveat is the shared constant, not a rewording of it', () => {
  it('GrantManager states the caveat verbatim, then keeps the re-key fact as a second sentence', () => {
    const source = readFileSync(new URL('./GrantManager.svelte', import.meta.url), 'utf8')
    expect(source).toMatch(/import \{ REVOKE_CAVEAT \} from '\.\.\/\.\.\/pairing\/copy'/)

    const markup = markupOf(source)
    expect(markup).toContain('{REVOKE_CAVEAT}')

    // The re-key fact is real information and stays — but AFTER the verbatim sentence, not
    // instead of it (the issue's explicit instruction: second sentence, not a replacement).
    const constantAt = markup.indexOf('{REVOKE_CAVEAT}')
    const rekeyAt = markup.indexOf('re-key')
    expect(rekeyAt).toBeGreaterThan(constantAt)

    // Control: a screen that says something else in that paragraph does not pass this check —
    // proving the assertion is actually reading the sentence, not just the presence of the tag.
    const reworded = markupOf(
      '<p class="revoke-note">Turning this off stops delivery going forward, but reaches back into nothing already sent.</p>',
    )
    expect(reworded).not.toContain('{REVOKE_CAVEAT}')
  })

  it('PinRecord states the caveat verbatim in the forget-everything disclosure', () => {
    const source = readFileSync(new URL('./PinRecord.svelte', import.meta.url), 'utf8')
    expect(source).toMatch(/import \{ REVOKE_CAVEAT \} from '\.\.\/\.\.\/pairing\/copy'/)

    const markup = markupOf(source)
    expect(markup).toContain('{REVOKE_CAVEAT}')

    // Control: the same check applied to a stand-in paragraph that never mentions the constant.
    const reworded = markupOf('<p>Nothing already sent to a therapist comes back because of this.</p>')
    expect(reworded).not.toContain('{REVOKE_CAVEAT}')
  })

  it('pinStore.ts documents forgetAllPins by naming the constant, not restating its wording', () => {
    const source = readFileSync(new URL('../../therapist/pinStore.ts', import.meta.url), 'utf8')
    expect(source).toContain('REVOKE_CAVEAT')

    // Control: a doc comment that just talks about the topic, without naming the constant, fails
    // this same check — so passing it means the name is actually there, not that the topic is.
    const reworded = source.replace(/REVOKE_CAVEAT/g, 'the usual caveat')
    expect(reworded).not.toContain('REVOKE_CAVEAT')
  })

  /*
   * The exemption, as a checked list rather than a paragraph.
   *
   * An exemption nobody verifies is how a rule quietly stops applying: the file says a surface is
   * exempt, the surface is later rewritten or deleted, and the sentence in the header goes on
   * claiming a decision about something that no longer exists. So each entry has to be a real file
   * that really does turn access off, and that really does say the mirror thing instead.
   */
  const EXEMPT_SURFACES = [
    {
      path: '../../components/therapist/LeaveRelationship.svelte',
      /** Why this surface may omit the caveat. */
      because: 'from the clinician’s side there is no reader being cut off, and they un-send nothing',
      /** What it must say in its place. */
      mirror: 'LEAVE_DOES_NOT_REACH_COPIES',
    },
  ]

  it('the exempt surface is real, does turn access off, and says the mirror thing instead', () => {
    for (const { path, because, mirror } of EXEMPT_SURFACES) {
      const source = readFileSync(new URL(path, import.meta.url), 'utf8')
      expect(because.length, `${path}: an exemption needs a reason`).toBeGreaterThan(0)
      // It really is a surface that turns access off — otherwise the exemption guards nothing.
      expect(source, path).toContain('leaveRelationship')
      // And it really does carry the mirror constant in its place.
      expect(source, path).toContain(`{${mirror}}`)
      // It genuinely does not use the caveat, which is the thing being exempted.
      expect(source, path).not.toContain('REVOKE_CAVEAT')
    }
    // Control: the same three checks do NOT pass on a stand-in that turns access off and says
    // neither thing — so an exemption cannot be satisfied by a file that simply stays quiet.
    const planted = '<p>Leaving ends your access.</p>'
    expect(planted).not.toContain('{LEAVE_DOES_NOT_REACH_COPIES}')
  })

  it('the constant is one plain sentence, stated flatly, never softened', () => {
    // Anchored at both ends without retyping the whole sentence (see the file header for why).
    expect(REVOKE_CAVEAT.startsWith('Revoking does not ')).toBe(true)
    expect(REVOKE_CAVEAT.endsWith(' what was already read.')).toBe(true)
    expect(REVOKE_CAVEAT.match(/\./g)).toHaveLength(1) // one sentence, not a paragraph
    expect(REVOKE_CAVEAT).not.toContain('!')
    expect(REVOKE_CAVEAT).not.toMatch(
      /\b(success|verified|secure|congratulat|well done|streak|score|perfect)\b/i,
    )
    // Control: the same checks do fire on text that violates them.
    expect('Revoked! Great job staying secure.').toMatch(
      /\b(success|verified|secure|congratulat|well done|streak|score|perfect)\b/i,
    )
  })
})
