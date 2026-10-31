/*
 * Every duty a role card lists either names the file that does it or says "Not built" (built.ts).
 */
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { BUILT, NOT_BUILT_CHIP, NOT_BUILT_MEANS, isBuilt } from './built'
import { CAPABILITIES, type PracticeCapability } from './capabilities'
import { roleFacts, seatableRoleFacts } from './orgRoles'

const read = (rel: string) => readFileSync(new URL(rel, import.meta.url), 'utf8')

/** For each duty claimed as built, the source that does it and the call it must contain. */
const EVIDENCE: Record<string, { file: string; contains: string }> = {
  'member.manage': { file: '../components/practice/AddMemberPanel.svelte', contains: 'client.addMember(' },
  'role.assign': { file: '../components/practice/RosterPanel.svelte', contains: 'client.changeRole(' },
  'audit.review': { file: '../components/practice/AuditPanel.svelte', contains: 'formatAuditInstant(event.ts)' },
  'assign.questionnaire': { file: '../components/therapist/AssignSurface.svelte', contains: "hasCapability(grant, 'assign.questionnaire')" },
  'assign.task': { file: '../components/therapist/AssignSurface.svelte', contains: "hasCapability(grant, 'assign.task')" },
  'assign.goal': { file: '../components/therapist/AssignSurface.svelte', contains: "hasCapability(grant, 'assign.goal')" },
  'assign.reminder': { file: '../components/therapist/AssignSurface.svelte', contains: "hasCapability(grant, 'assign.reminder')" },
  authorGamePlan: { file: '../components/therapist/TherapistPortal.svelte', contains: "hasCapability(grant, 'authorGamePlan')" },
  'server.operate': { file: '../components/admin/AdminConsole.svelte', contains: 'fetchImpl(`${baseUrl}${ep.path}`' },
  'ops.metrics': { file: '../components/admin/AdminConsole.svelte', contains: 'fetchImpl(`${baseUrl}${ep.path}`' },
}

describe('which role duties are built', () => {
  it('every duty claimed as built has its call in the console that does it', () => {
    const claimed = Object.keys(BUILT).sort()
    expect(claimed.length).toBeGreaterThan(0)
    expect(Object.keys(EVIDENCE).sort()).toEqual(claimed)
    for (const id of claimed) {
      expect(CAPABILITIES[id as PracticeCapability], id).toBeDefined()
      const { file, contains } = EVIDENCE[id]!
      expect(read(file), `${id}: ${file}`).toContain(contains)
    }
    // Positive control: the check can see a call that is not there.
    expect(read('../components/therapist/AssignSurface.svelte')).not.toContain("hasCapability(grant, 'assign.largeAssessment')")
    expect(isBuilt('assign.largeAssessment')).toBe(false)
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
    const src = read('../components/practice/RolePicker.svelte')
    expect(src).toContain('{#if !action.built}<Chip tone="neutral">{NOT_BUILT_CHIP}</Chip>{/if}')
    expect(src).toContain('{NOT_BUILT_MEANS}')
    expect(NOT_BUILT_CHIP).toBe('Not built')
    expect(NOT_BUILT_MEANS).toMatch(/does not let anyone do them/)
  })
})
