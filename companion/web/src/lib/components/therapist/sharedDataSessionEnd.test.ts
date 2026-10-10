import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/*
 * A 401 on the share fetch means the server ended the session. The page locks and shows the
 * after-lock line; it never says the share was tampered with, which is a cause it cannot know
 * (#391). A share that really fails its checks keeps the tamper sentence.
 */
const read = (n: string) => readFileSync(fileURLToPath(new URL(n, import.meta.url)), 'utf8')
const VIEW = read('./SharedDataView.svelte')
const PORTAL = read('./TherapistPortal.svelte')
const TAMPER = 'it did not verify against the pinned owner key, or it was tampered with'

/** The text of the catch block, up to the `finally`. */
const catchBlock = (src: string) => src.slice(src.indexOf('} catch (e) {'), src.indexOf('} finally {'))

describe('the clinician console when the server ends the session (#391)', () => {
  it('positive control: the catch block was found and holds the tamper sentence', () => {
    expect(catchBlock(VIEW)).toContain(TAMPER)
  })

  it('a 401 calls onended and returns before any message is chosen', () => {
    const c = catchBlock(VIEW)
    const at401 = c.indexOf('e.status === 401')
    expect(at401).toBeGreaterThan(-1)
    expect(c.indexOf('onended?.()')).toBeGreaterThan(at401)
    expect(c.indexOf('return', at401)).toBeLessThan(c.indexOf('error ='))
    expect(c.indexOf('error =')).toBeGreaterThan(at401)
  })

  it('the portal answers it with lock(), the same path the idle guard takes', () => {
    expect(PORTAL).toContain('onended={lock}')
    expect(PORTAL).toMatch(/if \(ctx && !live\) lock\(\)/)
  })

  it('a server failure that is not a 401 does not claim tampering either', () => {
    expect(catchBlock(VIEW)).toContain('e instanceof PortalError')
  })
})
