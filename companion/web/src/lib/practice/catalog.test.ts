import { describe, it, expect } from 'vitest'

import { ALL_CAPABILITIES } from '../assignments/types'
import { describeCapability } from '../assignments/describe'
import {
  ALL_PRACTICE_CAPABILITIES,
  CAPABILITIES,
  PATIENT_GRANT_ONLY_CAPABILITIES,
  PLANES,
  PLANE_LABEL,
  PLANE_RULE,
  READ_CLINICAL_CAPABILITIES,
  READ_MINTING_CAPABILITIES,
  describePracticeCapability,
  factsOf,
  frictionRank,
  isGrantVocabularyCapability,
  type Plane,
} from './capabilities'
import { ROLES, ROLE_IDS, roleById, rolesWith, type RoleId } from './roles'

/**
 * MODEL INTEGRITY — the boring half, without which the invariant suite next door is guessing.
 *
 * threePlane.test.ts asserts things ABOUT the catalog: no admin role reads, the document and the
 * code list the same roles. Every one of those assertions assumes the catalog is well-formed — that
 * a preset names capabilities that exist, that no id appears twice, that every capability a surface
 * might render has copy to render. Those assumptions are cheap to check and expensive to be wrong
 * about: a preset naming a capability the catalog does not define would throw at render time in the
 * practice UI, and a duplicated id would let a filter miss half of what it was pointed at.
 *
 * The other reason this file exists separately: it is where a contributor extending the vocabulary
 * will get their feedback. The invariant suite is about a design claim and should stay readable as
 * one argument; "you forgot the copy for the capability you just added" belongs here.
 */

describe('the vocabulary is well-formed', () => {
  it('has every capability the shipped grant model already defined, under its original id', () => {
    // The extension must not fork the vocabulary. If one of the eight ever disappears from here,
    // the practice UI and GrantManager.svelte would be describing two different permission sets.
    expect(ALL_CAPABILITIES.length).toBe(8)
    const missing = ALL_CAPABILITIES.filter((c) => !ALL_PRACTICE_CAPABILITIES.includes(c))
    expect(missing, 'the practice catalog dropped a shipped grant capability').toEqual([])
  })

  it('lists every capability exactly once', () => {
    expect(new Set(ALL_PRACTICE_CAPABILITIES).size).toBe(ALL_PRACTICE_CAPABILITIES.length)
    expect(ALL_PRACTICE_CAPABILITIES.length).toBe(Object.keys(CAPABILITIES).length)
  })

  it('gives every capability a plane, a read posture, an authorization, a price and a minting answer', () => {
    const reads = new Set(['none', 'own', 'others'])
    const auths = new Set(['role', 'role-and-grant', 'grant-only', 'break-glass'])
    const frictions = new Set(['none', 'step-up', 'maximum'])
    for (const cap of ALL_PRACTICE_CAPABILITIES) {
      const f = factsOf(cap)
      expect(PLANES, cap).toContain(f.plane)
      expect(reads.has(f.reads), `${cap} reads=${f.reads}`).toBe(true)
      expect(auths.has(f.authorization), `${cap} authorization=${f.authorization}`).toBe(true)
      expect(frictions.has(f.friction), `${cap} friction=${f.friction}`).toBe(true)
      // `mintsRead` is required rather than optional in the type, so a new capability cannot be
      // added without its author answering "does exercising this authorize somebody else's
      // reading?" — which is the question the whole patient-root-of-consent rule turns on.
      expect(typeof f.mintsRead, `${cap} does not say whether it mints read for somebody else`).toBe('boolean')
    }
  })

  it('is ordered data, then control, then monitoring, in unbroken blocks', () => {
    // Not cosmetic. The file's whole argument is legible from its order — everything that touches
    // content is in the first block, and nothing in that block is conferred by a title. An entry
    // that drifts into the wrong block is an entry a reviewer reads in the wrong context.
    const order: Plane[] = ['data', 'control', 'monitoring']
    const seen = ALL_PRACTICE_CAPABILITIES.map((c) => CAPABILITIES[c].plane)
    const blocks = seen.filter((p, i) => i === 0 || p !== seen[i - 1])
    expect(blocks).toEqual(order)
  })

  it('every plane has a label and a sentence a person could read', () => {
    for (const p of PLANES) {
      expect(PLANE_LABEL[p].length).toBeGreaterThan(0)
      expect(PLANE_RULE[p].length).toBeGreaterThan(40)
    }
  })

  it('the derived sets agree with the facts they are derived from', () => {
    expect(READ_CLINICAL_CAPABILITIES).toEqual(
      ALL_PRACTICE_CAPABILITIES.filter((c) => CAPABILITIES[c].reads === 'others'),
    )
    expect(PATIENT_GRANT_ONLY_CAPABILITIES).toEqual(
      ALL_PRACTICE_CAPABILITIES.filter(
        (c) => CAPABILITIES[c].authorization === 'grant-only' || CAPABILITIES[c].authorization === 'break-glass',
      ),
    )
    expect(READ_MINTING_CAPABILITIES).toEqual(ALL_PRACTICE_CAPABILITIES.filter((c) => CAPABILITIES[c].mintsRead))
  })

  it('prices are ordered', () => {
    expect(frictionRank('none')).toBeLessThan(frictionRank('step-up'))
    expect(frictionRank('step-up')).toBeLessThan(frictionRank('maximum'))
  })
})

describe('every capability can be shown to a person', () => {
  it('has a title and a description', () => {
    for (const cap of ALL_PRACTICE_CAPABILITIES) {
      const copy = describePracticeCapability(cap)
      expect(copy, cap).toBeDefined()
      expect(copy.title.length, cap).toBeGreaterThan(3)
      expect(copy.desc.length, cap).toBeGreaterThan(20)
      // Mechanical sentences, matching assignments/describe.ts: a title is a phrase, a description
      // is at least one finished sentence.
      expect(copy.title.endsWith('.'), cap).toBe(false)
      expect(copy.desc.endsWith('.'), cap).toBe(true)
    }
  })

  it('delegates the shipped eight rather than restating them', () => {
    // One capability, one wording, whichever surface renders it. If this ever diverges, the owner
    // console and the practice console are describing the same permission differently to the same
    // person — which is how somebody ends up granting what they thought they were refusing.
    for (const cap of ALL_CAPABILITIES) {
      expect(isGrantVocabularyCapability(cap)).toBe(true)
      expect(describePracticeCapability(cap)).toEqual(describeCapability(cap))
    }
    expect(isGrantVocabularyCapability('member.manage')).toBe(false)
  })
})

describe('the role catalog is well-formed', () => {
  it('has unique ids and unique document labels', () => {
    expect(new Set(ROLES.map((r) => r.id)).size).toBe(ROLES.length)
    expect(new Set(ROLES.map((r) => r.docLabel)).size).toBe(ROLES.length)
    expect(ROLE_IDS).toEqual(ROLES.map((r) => r.id))
  })

  it('names only capabilities the vocabulary defines, and none twice', () => {
    for (const role of ROLES) {
      const unknown = role.manages.filter((c) => !ALL_PRACTICE_CAPABILITIES.includes(c))
      expect(unknown, `${role.id} lists capabilities the catalog does not define`).toEqual([])
      expect(new Set(role.manages).size, `${role.id} lists a capability twice`).toBe(role.manages.length)
    }
  })

  it('gives every role something to do and something to be called', () => {
    for (const role of ROLES) {
      expect(role.manages.length, `${role.id} has an empty preset`).toBeGreaterThan(0)
      expect(role.label.length, role.id).toBeGreaterThan(2)
      expect(role.managesSummary.length, role.id).toBeGreaterThan(5)
      expect(role.readsSummary.length, role.id).toBeGreaterThan(2)
    }
  })

  it('the psychiatrist preset is the clinician preset plus the tools they may publish', () => {
    // The document says "same as clinician; may publish Validated/Adapted tools" — literally same
    // plus one. Written as a check rather than as a comment because "same as X" is the kind of
    // claim that quietly stops being true when only one of the two is edited.
    const clinician = roleById('clinician').manages
    const psychiatrist = roleById('psychiatrist').manages
    const missing = clinician.filter((c) => !psychiatrist.includes(c))
    expect(missing, 'the psychiatrist no longer has everything the clinician has').toEqual([])
    expect(psychiatrist.filter((c) => !clinician.includes(c))).toEqual(['tool.publishValidated'])
  })

  it('the assistant preset is strictly narrower than the clinician’s', () => {
    // § Consent model: "an assistant's is narrower than the clinician's". The document does not
    // enumerate the narrowing, so roles.ts chooses one and flags the choice; this pins the part
    // that is not a choice — it must be a subset, and a proper one.
    const clinician = roleById('clinician').manages
    const assistant = roleById('assistant').manages
    expect(assistant.filter((c) => !clinician.includes(c))).toEqual([])
    expect(assistant.length).toBeLessThan(clinician.length)
  })

  it('roleById finds every role and refuses an id it does not know', () => {
    for (const id of ROLE_IDS) expect(roleById(id).id).toBe(id)
    expect(() => roleById('district-manager' as RoleId)).toThrow(/unknown practice role/)
  })

  it('rolesWith answers "who can do this?"', () => {
    expect(rolesWith('access.killSwitch').map((r) => r.id)).toEqual(['org-admin'])
    expect(rolesWith('schedule.manage').map((r) => r.id)).toEqual(['front-desk'])
    // Both ways of authorizing somebody else's reading come back with one name, and it is the same
    // name. A practice UI asking "who can put a reader in front of this record?" must not be able
    // to render an administrator, whichever of the two doors it asks about.
    for (const cap of READ_MINTING_CAPABILITIES) expect(rolesWith(cap).map((r) => r.id), cap).toEqual(['patient'])
    expect(READ_MINTING_CAPABILITIES.length).toBeGreaterThan(1)
    // No role holds a read scope, so the lookup that matters most comes back empty — and the
    // practice UI can say so honestly instead of implying a title exists that would grant it.
    for (const cap of READ_CLINICAL_CAPABILITIES) expect(rolesWith(cap), cap).toEqual([])
  })
})
