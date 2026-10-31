/*
 * Every duty a role card lists either names the file that does it or says "Not built" (built.ts).
 */
import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { BUILT, NOT_BUILT_CHIP, NOT_BUILT_MEANS, isBuilt } from './built'
import { CAPABILITIES, type PracticeCapability } from './capabilities'
import { roleFacts, seatableRoleFacts } from './orgRoles'

const REPO = fileURLToPath(new URL('../../../../../', import.meta.url))

describe('which role duties are built', () => {
  it('every duty claimed as built names a file that exists', () => {
    const entries = Object.entries(BUILT)
    expect(entries.length).toBeGreaterThan(0)
    for (const [id, file] of entries) {
      expect(CAPABILITIES[id as PracticeCapability], id).toBeDefined()
      expect(existsSync(REPO + file), `${id}: ${file}`).toBe(true)
    }
    // Positive control: the check can see a file that does not exist.
    expect(existsSync(REPO + 'companion/web/src/lib/practice/NoSuchScreen.svelte')).toBe(false)
  })

  it("the front desk's duties all say Not built, because a front desk can only read the roster", () => {
    const desk = roleFacts('front_desk')
    expect(desk.actions.length).toBeGreaterThan(0)
    expect(desk.actions.filter((a) => a.built)).toEqual([])
  })

  it("the practice admin's membership, role and log duties are built (positive control)", () => {
    expect(isBuilt('member.manage')).toBe(true)
    expect(isBuilt('role.assign')).toBe(true)
    expect(isBuilt('audit.review')).toBe(true)
    expect(isBuilt('access.killSwitch')).toBe(false)
  })

  it('every seatable role states built or not for every duty', () => {
    for (const role of seatableRoleFacts()) for (const a of role.actions) expect(typeof a.built).toBe('boolean')
  })

  it('the role picker shows the chip and the sentence', () => {
    const src = readFileSync(new URL('../components/practice/RolePicker.svelte', import.meta.url), 'utf8')
    expect(src).toContain('{#if !action.built}<Chip tone="neutral">{NOT_BUILT_CHIP}</Chip>{/if}')
    expect(src).toContain('{NOT_BUILT_MEANS}')
    expect(NOT_BUILT_CHIP).toBe('Not built')
    expect(NOT_BUILT_MEANS).toMatch(/does not let anyone do them/)
  })
})
