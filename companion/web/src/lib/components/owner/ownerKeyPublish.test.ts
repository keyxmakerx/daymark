/*
 * The publish panel, over its source — node has no renderer, and these are properties of the file.
 *
 * Two claims worth pinning here rather than in publishKey.ts, because they are about the order this
 * component does things in: the sentence that says the act cannot be undone is rendered BEFORE the
 * button that performs it, and what the panel believes is published comes from a read-back rather
 * than from the status code the POST returned.
 */
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const source = readFileSync(fileURLToPath(new URL('./OwnerKeyPublish.svelte', import.meta.url)), 'utf8')
const code = source
  .replace(/<!--[\s\S]*?-->/g, '')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/(?<!:)\/\/[^\n]*/g, '')

describe('the panel that sends the owner key', () => {
  it('says it cannot be undone above the button that does it', () => {
    const caveat = code.indexOf('{CANNOT_BE_REPLACED}')
    const button = code.indexOf('{busy ? PUBLISH_BUSY : PUBLISH_ACTION}')
    expect(caveat).toBeGreaterThan(-1)
    expect(button).toBeGreaterThan(caveat)
  })

  it('believes the read-back, not the status code', () => {
    /*
     * The POST answers 204 or 409 and neither settles what a clinician will pin — only what the
     * server hands back does. So publishedOwnerKeys is called again after publishing, and what the
     * panel renders is classified from THAT.
     */
    const publish = code.indexOf('publishOwnerKeys')
    const readBack = code.indexOf('publishedOwnerKeys', publish)
    expect(publish).toBeGreaterThan(-1)
    expect(readBack).toBeGreaterThan(publish)
    expect(code).toMatch(/published = await client\.publishedOwnerKeys\(relRef\)[\s\S]{0,80}phase = classify\(published\)/)
  })

  it('offers no retry on the one state nothing can repair', () => {
    // A button on the 'different' branch would answer 409 forever and teach someone that pressing
    // it harder might work. The copy says the way out is a new connection; the markup must agree.
    const branch = code.slice(code.indexOf("{:else if phase === 'different'}"), code.indexOf("{:else}"))
    expect(branch).toContain('DIFFERENT_KEY_PUBLISHED')
    expect(branch).not.toContain('onclick={send}')
  })

  it('the branch detector detects', () => {
    // Guard the guard above: prove the slice really is the 'different' branch and really would see
    // a send button if one were there.
    const branch = code.slice(code.indexOf("{:else if phase === 'different'}"), code.indexOf("{:else}"))
    expect(branch.length).toBeGreaterThan(50)
    expect(`${branch}<button onclick={send}>x</button>`).toContain('onclick={send}')
  })
})
