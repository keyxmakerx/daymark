import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  READ_POSTURE_CHIP,
  SEATABLE_ROLES,
  SEATABLE_ROLE_WIRES,
  UNSEATABLE,
  roleFacts,
  roleForWire,
  roleLabel,
  rolesThatMintReads,
  seatableRoleFacts,
  seatableRolesThatReadClinical,
  wireForRole,
} from './orgRoles'
import { ROLES, roleById, type ReadPosture } from './roles'
import { CAPABILITIES } from './capabilities'

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS SUITE IS FOR
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * One question, asked twice: does the role picker show facts that come from the role model, or
 * facts somebody typed into markup?
 *
 * The distinction is not stylistic. threePlane.test.ts enforces that no role's preset contains a
 * capability which reads another person's clinical content — that is the machine form of "an admin
 * can revoke anyone and see who-accessed-what, yet cannot read a single clinical note". If that
 * invariant ever broke, a hand-typed reassurance under a role name would keep reassuring the person
 * doing the assigning, at the exact moment it had stopped being true, while the failure sat in a CI
 * log nobody on that screen is reading. Derived, the reassurance cannot outlive the fact.
 *
 * So the suite does two things. It checks that every fact the picker renders is the model's own
 * value, cell for cell. And it reads RolePicker.svelte and asserts that no role's label or summary
 * appears in it as a literal — the absence that makes the first check meaningful, since a component
 * could perfectly well import the model and then ignore it.
 *
 * The source read is over markup with comments stripped, following the convention in
 * components/invariants.tree.test.ts: an honest header comment has to be able to name the thing it
 * explains, and a guard satisfied by an explanation instead of by the code is the vacuity this
 * repository already writes tests to avoid.
 */

const PICKER = fileURLToPath(new URL('../components/practice/RolePicker.svelte', import.meta.url))
const PICKER_SOURCE = readFileSync(PICKER, 'utf8')

/** Comments removed — Svelte/HTML, block and line — leaving what actually ships. */
const PICKER_CODE = PICKER_SOURCE.replace(/<!--[\s\S]*?-->/g, '')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/(?<!:)\/\/[^\n]*/g, '')

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The join between the server's six and the catalog's eight.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the seatable roles are a subset of the catalog, and the absences are deliberate', () => {
  it('names the six wire values OrgRole declares, in the server’s order', () => {
    // Typed out here on purpose: this is the wire contract, and the point of the assertion is that
    // a change to it has to be made in two places by somebody who noticed.
    expect(SEATABLE_ROLE_WIRES).toEqual([
      'clinician',
      'psychiatrist',
      'therapist_assistant',
      'front_desk',
      'supervisor',
      'org_admin',
    ])
  })

  it('every seatable role points at a real row in the catalog', () => {
    expect(SEATABLE_ROLES.length).toBe(6)
    for (const { wire, id } of SEATABLE_ROLES) {
      const role = roleById(id) // throws if the catalog does not have it
      expect(roleForWire(wire)).toBe(role)
      expect(wireForRole(id)).toBe(wire)
    }
  })

  it('accounts for all eight catalog roles: six seatable, two named as unseatable', () => {
    // The completeness check. A role added to the catalog and to neither list would simply vanish
    // from this interface, which is the failure mode a dropdown makes invisible.
    const seatable = SEATABLE_ROLES.map((r) => r.id)
    const unseatable = UNSEATABLE.map((r) => r.id)
    expect([...seatable, ...unseatable].sort()).toEqual(ROLES.map((r) => r.id).sort())
    expect(seatable.filter((id) => unseatable.includes(id))).toEqual([])
  })

  it('refuses to seat the patient or the operator, and says why on the screen', () => {
    expect(UNSEATABLE.map((r) => r.id)).toEqual(['patient', 'platform-sysadmin'])
    for (const entry of UNSEATABLE) {
      // The label is the catalog's, not a second spelling of it.
      expect(entry.label).toBe(roleById(entry.id).label)
      // And the reason is a reason, not a shrug.
      expect(entry.why.length).toBeGreaterThan(80)
    }
    expect(UNSEATABLE[0].why).toMatch(/own their keys|not a member/i)
  })

  it('shows an unknown role verbatim rather than guessing at it', () => {
    // A server ahead of this build sends a role this catalog does not model. Labelling it as the
    // first entry in the list would quietly tell an administrator somebody is a clinician.
    expect(roleForWire('nurse_practitioner')).toBeNull()
    expect(roleLabel('nurse_practitioner')).toBe('nurse_practitioner')
    expect(roleLabel('front_desk')).toBe(roleById('front-desk').label)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The facts, cell for cell.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('every fact the picker renders is the model’s own value', () => {
  it('carries the catalog’s two summary cells through unchanged', () => {
    for (const facts of seatableRoleFacts()) {
      const role = roleById(facts.id)
      expect(facts.label).toBe(role.label)
      expect(facts.docLabel).toBe(role.docLabel)
      // These two are the document's own table cells, transcribed in roles.ts and checked against
      // the document by catalog.test.ts. Passing them through unchanged is what connects the
      // screen to the specification.
      expect(facts.managesSummary).toBe(role.managesSummary)
      expect(facts.readsSummary).toBe(role.readsSummary)
      expect(facts.reads).toBe(role.reads)
    }
  })

  it('lists exactly the preset’s actions, with each action’s own plane', () => {
    for (const facts of seatableRoleFacts()) {
      const role = roleById(facts.id)
      expect(facts.actions.map((a) => a.id)).toEqual([...role.manages])
      for (const action of facts.actions) {
        expect(action.plane).toBe(CAPABILITIES[action.id].plane)
        expect(action.title.length).toBeGreaterThan(0)
        expect(action.description.length).toBeGreaterThan(20)
      }
      // The planes are the role's declared planes, in the role's order.
      expect(facts.planeLabels.length).toBe(role.planes.length)
    }
  })

  it('reports the clinical-read list as a filter, which is empty for every seatable role', () => {
    // The central claim, evaluated. Presence first: the filter can find something, so an empty
    // result below means the presets really are clean rather than the detector being broken.
    const detector = roleFacts('clinician')
    expect(detector.actions.length).toBeGreaterThan(5)
    expect(detector.actions.some((a) => a.readsOthersClinical)).toBe(false)

    for (const facts of seatableRoleFacts()) {
      expect(facts.clinicalReadActions, facts.wire).toEqual([])
      expect(facts.readMintingActions, facts.wire).toEqual([])
    }
    expect(seatableRolesThatReadClinical()).toEqual([])
  })

  it('finds exactly one role in the whole catalog that can authorize somebody else to read', () => {
    // And it is a role a practice cannot seat. That is the sentence the limits screen renders as a
    // computed list rather than as a claim.
    const minters = rolesThatMintReads()
    expect(minters.map((r) => r.id)).toEqual(['patient'])
    expect(wireForRole('patient')).toBeNull()
  })

  it('has a chip phrase for every read posture the catalog uses', () => {
    // Totality, so a posture added later cannot arrive with no phrase and fall back to nothing.
    const used = new Set<ReadPosture>(ROLES.map((r) => r.reads))
    expect(used.size).toBeGreaterThan(2)
    for (const posture of used) {
      expect(READ_POSTURE_CHIP[posture], posture).toBeTruthy()
    }
    // The one that carries the central claim must not read as a permission being granted, and the
    // three that do involve reading must all name the grant as the source.
    expect(READ_POSTURE_CHIP.never).toMatch(/^No\b/)
    for (const posture of ['granted', 'granted-narrowed', 'only-by-separate-grant'] as ReadPosture[]) {
      expect(READ_POSTURE_CHIP[posture], posture).toMatch(/grant/i)
    }
  })

  it('throws rather than rendering an empty fact sheet for an unknown role', () => {
    // A picker that silently rendered nothing would be telling somebody this role does nothing,
    // confidently and wrongly.
    expect(() => roleFacts('nurse_practitioner' as never)).toThrow(/no catalog role/)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   The picker draws from the model, and holds no copy of it.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('the role picker’s rendered facts come from the role model', () => {
  it('the source was read, and the comment stripper left the markup', () => {
    // Non-vacuity for everything below: if the read or the strip failed, the absence assertions
    // would pass against an empty string.
    expect(PICKER_SOURCE.length).toBeGreaterThan(2000)
    expect(PICKER_CODE).toContain('<fieldset')
    expect(PICKER_CODE).toContain('{#each roles as role')
    expect(PICKER_CODE.length).toBeLessThan(PICKER_SOURCE.length)
  })

  it('imports the catalog and renders its fields rather than restating them', () => {
    expect(PICKER_CODE).toContain("from '../../practice/orgRoles'")
    expect(PICKER_CODE).toContain('seatableRoleFacts()')
    // The three facts that matter, each read off the record the model produced.
    expect(PICKER_CODE).toContain('{role.label}')
    expect(PICKER_CODE).toContain('{role.managesSummary}')
    expect(PICKER_CODE).toContain('{role.readsSummary}')
    expect(PICKER_CODE).toContain('READ_POSTURE_CHIP[role.reads]')
  })

  it('contains no role’s label or summary as a literal', () => {
    // THE assertion. A component may import the model and then ignore it; this is what rules that
    // out. Checked against every role in the catalog, including the two a practice cannot seat.
    const offenders: string[] = []
    for (const role of ROLES) {
      for (const [field, text] of [
        ['label', role.label],
        ['docLabel', role.docLabel],
        ['managesSummary', role.managesSummary],
        ['readsSummary', role.readsSummary],
      ] as const) {
        if (PICKER_CODE.includes(text)) offenders.push(`${role.id}.${field}: ${text}`)
      }
    }
    // The detector is shown working before it is trusted: these strings are what it is looking for,
    // and it finds them in the model's own output.
    const rendered = seatableRoleFacts()
      .map((f) => `${f.label} ${f.managesSummary} ${f.readsSummary}`)
      .join(' ')
    for (const role of ROLES.filter((r) => wireForRole(r.id))) {
      expect(rendered).toContain(role.readsSummary)
    }
    expect(offenders).toEqual([])
  })

  it('contains no wire role value as a literal either', () => {
    // The other half: a picker that hard-coded `'front_desk'` in its markup would be maintaining a
    // second list of the roles a practice can seat, free to disagree with the server's.
    const offenders = SEATABLE_ROLE_WIRES.filter((wire) => PICKER_CODE.includes(`'${wire}'`))
    expect(offenders).toEqual([])
  })

  it('renders the clinical-read finding as a computed list, with an alarm branch that exists', () => {
    // The drift case. If a preset ever acquires a read capability, this screen has to say so at
    // the moment of assignment rather than go on rendering the reassuring paragraph — so both
    // branches must be in the markup, and the condition must be the length of the computed list.
    expect(PICKER_CODE).toContain('selected.clinicalReadActions.length === 0')
    expect(PICKER_CODE).toContain('{selected.clinicalReadActions.join')
  })
})
