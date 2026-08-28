import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  AUDIT_IS_A_DIFFERENT_CHAIN,
  AUDIT_IS_METADATA_ONLY,
  CONSOLE_BUILD_STATE,
  CREATE_USES_THE_SERVER_TOKEN,
  CONSOLE_LEDE,
  MEMBERSHIP_IS_NOT_READ_ACCESS,
  NO_PATIENT_LIST,
  PLACEHOLDERS,
  PLACEHOLDER_WORD,
  REMOVAL_ENDS_A_MEMBERSHIP,
  SEAT_IS_AN_OFFER,
  SESSIONS_CUT_MEANS,
  SIGN_IN_IS_THE_PORTAL_CREDENTIAL,
  WHY_SOME_ACTS_COST_MORE,
} from './copy'
import {
  ORG_ACTION_LABEL,
  ORG_ACTOR_LABEL,
  orgAuditActionLabel,
  orgAuditActorLabel,
  orgAuditAnnotations,
  orgAuditSubjectLabel,
} from './audit'
import { roleById } from './roles'
import type { OrgAuditEvent } from './client'

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 * WHAT THIS SUITE IS FOR
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *
 * The practice console makes claims about a system whose central claim is that an administrator
 * cannot read a clinical note. Two ways for a screen like this to be dishonest, and both are easy
 * to arrive at without deciding to:
 *
 *   BY IMPLICATION. A roster with an "Add member" button beside it looks like a list of people who
 *   can see things. It is not. Every screen that seats or re-roles somebody therefore has to carry
 *   the correction, and the assertions below check it is on those screens rather than merely in
 *   this module.
 *
 *   BY FURNITURE. An unbuilt feature rendered as a plausible row of names and dates is
 *   indistinguishable from a bug to the person trying to decide whether the thing works. So every
 *   placeholder must say the word, and no component in this directory may contain something that
 *   looks like sample data.
 *
 * Presence before absence throughout: each detector is shown catching a planted example before it
 * is turned loose, and each list is shown non-empty before it is filtered.
 */

const COMPONENTS = fileURLToPath(new URL('../components/practice/', import.meta.url))

const componentFiles = readdirSync(COMPONENTS).filter((f) => f.endsWith('.svelte'))
const componentSource = new Map<string, string>(
  componentFiles.map((f) => [f, readFileSync(COMPONENTS + f, 'utf8')]),
)

/**
 * The prose a person actually reads: script and style gone, comments gone, tags removed.
 *
 * Svelte's own control blocks — `{#if}`, `{:else}`, `{/each}`, `{@const}` — go too. They are markup
 * rather than words, they never reach a reader, and leaving them in makes the register detectors
 * below fire on `{#if !session}`: a negation operator read as an exclamation mark. Interpolations
 * like `{PLACEHOLDER_WORD}` are deliberately KEPT, because whether a component renders a value from
 * the copy module is exactly what several assertions here are about.
 */
function proseOf(file: string): string {
  return (componentSource.get(file) ?? '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<script[\s\S]*?<\/script>/g, '')
    .replace(/<style[\s\S]*?<\/style>/g, '')
    .replace(/\{[#:/@][^}]*\}/g, ' ')
    .replace(/<[^>]*>/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

/** Code with commentary removed, for assertions about what a component does rather than says. */
function codeOf(file: string): string {
  return (componentSource.get(file) ?? '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(?<!:)\/\/[^\n]*/g, '')
}

/** Every fixed sentence this console is obliged to say, as one corpus. */
const COPY = [
  CONSOLE_LEDE,
  CONSOLE_BUILD_STATE,
  MEMBERSHIP_IS_NOT_READ_ACCESS,
  REMOVAL_ENDS_A_MEMBERSHIP,
  SESSIONS_CUT_MEANS,
  SEAT_IS_AN_OFFER,
  WHY_SOME_ACTS_COST_MORE,
  NO_PATIENT_LIST,
  AUDIT_IS_METADATA_ONLY,
  AUDIT_IS_A_DIFFERENT_CHAIN,
  ...PLACEHOLDERS.map((p) => `${p.title} ${p.body} ${p.specifiedAt}`),
]

describe('the suite has a subject', () => {
  it('found the console’s components and its copy', () => {
    expect(componentFiles.length).toBeGreaterThanOrEqual(7)
    expect(componentFiles).toContain('PracticeConsole.svelte')
    expect(componentFiles).toContain('RolePicker.svelte')
    expect(componentFiles).toContain('RosterPanel.svelte')
    expect(componentFiles).toContain('AddMemberPanel.svelte')
    expect(componentFiles).toContain('AuditPanel.svelte')
    expect(componentFiles).toContain('Placeholder.svelte')
    expect(COPY.length).toBeGreaterThan(15)
    expect(COPY.every((s) => s.length > 40)).toBe(true)
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (a) The two corrections, and where they are rendered.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(a) the console never implies that membership is access', () => {
  it('says what a role does and does not do, in as many words', () => {
    expect(MEMBERSHIP_IS_NOT_READ_ACCESS).toContain('does not give them access')
    expect(MEMBERSHIP_IS_NOT_READ_ACCESS).toContain('grant a patient signs on their own device')
    expect(MEMBERSHIP_IS_NOT_READ_ACCESS).toContain('nothing on this console can create one')
  })

  it('renders that sentence on every screen where somebody is seated or re-roled', () => {
    // The module holding a good sentence is worth nothing if the screen does not render it. The
    // role picker carries it above the options, so it is read before the choice rather than after;
    // the roster carries it in the card footer, which is joined to the table rather than beside it.
    for (const file of ['RolePicker.svelte', 'RosterPanel.svelte']) {
      expect(codeOf(file), file).toContain('MEMBERSHIP_IS_NOT_READ_ACCESS')
    }
    // And the add form reaches it through the picker it embeds, which is checked here rather than
    // assumed — an add form that dropped the picker would drop the correction with it.
    expect(codeOf('AddMemberPanel.svelte')).toContain('<RolePicker')
  })

  it('describes removal as ending a membership, never as revoking access', () => {
    expect(REMOVAL_ENDS_A_MEMBERSHIP).toContain('ends their membership')
    expect(REMOVAL_ENDS_A_MEMBERSHIP).toContain('does not end their access')
    expect(REMOVAL_ENDS_A_MEMBERSHIP).toContain('no grant is withdrawn')
    // The phrase the server's own file says this copy must never use. The detector is shown
    // catching a planted example first.
    const REVOKED = /access (is |was )?revoked|revoke their access|cut off their access/i
    expect(REVOKED.test('The member’s access was revoked.')).toBe(true)
    expect(COPY.filter((s) => REVOKED.test(s))).toEqual([])
  })

  it('renders the removal sentence at the removal, not in a manual', () => {
    expect(codeOf('RosterPanel.svelte')).toContain('REMOVAL_ENDS_A_MEMBERSHIP')
    expect(codeOf('RosterPanel.svelte')).toContain('Confirm removal')
  })

  it('qualifies the session count where the number appears and nowhere else', () => {
    expect(SESSIONS_CUT_MEANS).toContain('portal sessions ended')
    expect(SESSIONS_CUT_MEANS).toContain('not a count of grants withdrawn')
    const roster = codeOf('RosterPanel.svelte')
    expect(roster).toContain('outcomeWasRemoval')
    expect(roster).toContain('{#if outcomeWasRemoval}<p class="para">{SESSIONS_CUT_MEANS}</p>{/if}')
  })

  it('states that there is no patient list, as a design rather than a gap', () => {
    expect(NO_PATIENT_LIST).toContain('There is no list of this practice’s patients')
    expect(NO_PATIENT_LIST).toContain('no route assembles one')
    // It is NOT a placeholder: calling an absence-by-design "not built yet" would suggest it is on
    // its way, which is the opposite of what the design says.
    expect(PLACEHOLDERS.map((p) => p.body).join(' ')).not.toContain('patients')
    expect(codeOf('PracticeConsole.svelte')).toContain('NO_PATIENT_LIST')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (b) Placeholders say they are placeholders.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(b) nothing unbuilt is drawn as though it were built', () => {
  it('every placeholder names itself, its subject and where the real thing is specified', () => {
    expect(PLACEHOLDERS.length).toBeGreaterThanOrEqual(5)
    const ids = PLACEHOLDERS.map((p) => p.id)
    expect(new Set(ids).size).toBe(ids.length)
    for (const note of PLACEHOLDERS) {
      expect(note.title.length, note.id).toBeGreaterThan(5)
      // A placeholder that does not say what would be there is just a gap with a label on it.
      expect(note.body.length, note.id).toBeGreaterThan(80)
      expect(note.specifiedAt.length, note.id).toBeGreaterThan(10)
    }
  })

  it('the word reaches the interface, on every placeholder, from one place', () => {
    expect(PLACEHOLDER_WORD).toBe('Placeholder')
    // Rendered by the shared component, so a placeholder cannot be added without the marker.
    expect(codeOf('Placeholder.svelte')).toContain('{PLACEHOLDER_WORD}')
    expect(proseOf('Placeholder.svelte')).toContain('{PLACEHOLDER_WORD}')
    // And the console renders the catalogue through that component rather than by hand.
    const console_ = codeOf('PracticeConsole.svelte')
    expect(console_).toContain('{#each PLACEHOLDERS as note')
    expect(console_).toContain('<Placeholder')
  })

  it('promises no dates and no versions', () => {
    // "Coming in the next release" is a claim about a future nobody in this repository controls,
    // and it ages into a lie without anyone editing it.
    const PROMISE = /coming soon|next release|in a future version|by the end of|shipping in|v\d+\.\d+/i
    expect(PROMISE.test('Coming soon in v2.1')).toBe(true)
    expect(PLACEHOLDERS.filter((p) => PROMISE.test(`${p.title} ${p.body} ${p.specifiedAt}`))).toEqual([])
  })

  it('contains nothing that could pass for someone’s data', () => {
    // The worst failure mode this console has: a plausible name, a plausible date, a plausible
    // count, rendered as though it came from the server. The detector is calibrated on planted
    // examples, including ones in the shape a demo fixture usually takes.
    const SAMPLE = /\b(jane|john|dr\.|doe|acme|lorem ipsum|example\.com|@example)\b/i
    expect(SAMPLE.test('Dr. Jane Doe')).toBe(true)
    expect(SAMPLE.test('someone@example.com')).toBe(true)
    expect(SAMPLE.test('Read the roster')).toBe(false)
    const offenders = componentFiles.filter((f) => SAMPLE.test(proseOf(f)))
    expect(offenders).toEqual([])
  })

  it('renders an empty surface as empty rather than as a specimen row', () => {
    // Every list on this console has a no-data branch, and none of them fills the gap with an
    // example. A table that shows a fabricated row when it has nothing is the same lie as a
    // placeholder that does not say so.
    expect(codeOf('RosterPanel.svelte')).toContain('<EmptyState')
    expect(codeOf('AuditPanel.svelte')).toContain('<EmptyState')
    expect(codeOf('RosterPanel.svelte')).toContain('members === null')
    expect(codeOf('AuditPanel.svelte')).toContain('events === null')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (c) The register.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(c) the register is flat, adult and non-diagnostic', () => {
  it('claims no health, no pass and no score', () => {
    // There is no green in this system and no reassuring verdict available to it. Reassurance is
    // the absence of a callout, not the presence of a friendlier one.
    const CLAIMS = /\ball good\b|\bhealthy\b|\bsecure\b|\bverified\b|\bpassed\b|\bsuccess\b|✓|✔|\bgreen\b/i
    expect(CLAIMS.test('All good — everything is secure.')).toBe(true)
    expect(CLAIMS.test('Removing someone ends their membership.')).toBe(false)
    expect(COPY.filter((s) => CLAIMS.test(s))).toEqual([])
    for (const file of componentFiles) {
      expect(CLAIMS.test(proseOf(file)), file).toBe(false)
    }
  })

  it('renders no figure, grade, percentage or streak', () => {
    const GAMIFIED = /\b\d+%|\bscore\b|\bstreak\b|\bpoints\b|\bbadge\b|\blevel up\b|\bgrade\b/i
    expect(GAMIFIED.test('Your practice scored 92%')).toBe(true)
    expect(COPY.filter((s) => GAMIFIED.test(s))).toEqual([])
    for (const file of componentFiles) {
      expect(GAMIFIED.test(proseOf(file)), file).toBe(false)
    }
  })

  it('keeps the tone flat: no cheer, no exclamation, no emoji', () => {
    // Somebody may be reading this at two in the morning on a bad night.
    const CHEER = /!|\bwelcome\b|\bgreat\b|\bawesome\b|\bnice work\b|\bwell done\b|\boops\b/i
    expect(CHEER.test('Welcome! Great work.')).toBe(true)
    expect(COPY.filter((s) => CHEER.test(s))).toEqual([])
    const EMOJI = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u
    expect(EMOJI.test('done 🎉')).toBe(true)
    for (const file of componentFiles) {
      expect(CHEER.test(proseOf(file)), file).toBe(false)
      expect(EMOJI.test(proseOf(file)), file).toBe(false)
    }
  })

  it('never describes a person’s data or implies a clinical judgement', () => {
    // This console is control-plane furniture. If it started characterising anybody's material it
    // would be doing something no route it calls could support.
    const CLINICAL = /\bdiagnos|\bsymptom|\bimprov(ing|ed)\b|\bat risk\b|\bconcerning\b/i
    expect(CLINICAL.test('This client is at risk.')).toBe(true)
    expect(COPY.filter((s) => CLINICAL.test(s))).toEqual([])
    for (const file of componentFiles) {
      expect(CLINICAL.test(proseOf(file)), file).toBe(false)
    }
  })

  it('says what the build is rather than letting a first pass look finished', () => {
    expect(CONSOLE_BUILD_STATE).toContain('First interface')
    expect(CONSOLE_BUILD_STATE).toContain('marked as placeholders')
    expect(codeOf('PracticeConsole.svelte')).toContain('CONSOLE_BUILD_STATE')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (d) The practice's log.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(d) the practice log is labelled as the control-plane record it is', () => {
  it('has a label for every action the org routes append', () => {
    // The six from AuditStore's ORG_* entries. A missing one renders as a raw wire string, which is
    // survivable; the assertion is here so it is a decision rather than an accident.
    for (const action of [
      'org.created',
      'org.member_added',
      'org.member_accepted',
      'org.member_role_changed',
      'org.member_removed',
      'org.action_denied',
    ]) {
      expect(ORG_ACTION_LABEL[action], action).toBeTruthy()
      expect(orgAuditActionLabel(action)).toBe(ORG_ACTION_LABEL[action])
    }
  })

  it('shows an action it has never heard of verbatim rather than hiding it', () => {
    // A log with rows missing is worse than a log with rows a reader has to look up.
    expect(orgAuditActionLabel('org.something_new')).toBe('org.something_new')
    expect(orgAuditActorLabel('some_new_actor')).toBe('some_new_actor')
    expect(ORG_ACTOR_LABEL['org_admin']).toBeTruthy()
  })

  it('does not promote a membership event into a data-plane one', () => {
    const PROMOTED = /granted access|access granted|access revoked|read access/i
    expect(PROMOTED.test('Access granted to member')).toBe(true)
    expect(Object.values(ORG_ACTION_LABEL).filter((l) => PROMOTED.test(l))).toEqual([])
    expect(ORG_ACTION_LABEL['org.member_removed']).toContain('removed from the practice')
  })

  it('reads role-valued annotations through the role catalog', () => {
    // Not through a second lookup typed out beside it, which is how a log starts disagreeing with
    // the roster on the same screen.
    const event: OrgAuditEvent = {
      seq: 4,
      ts: 1_700_000_000_000,
      actor: 'org_admin',
      action: 'org.member_role_changed',
      objectRef: 'ada',
      meta: { actor: 'admin-1', from: 'front_desk', to: 'org_admin' },
      entryHash: 'abc',
    }
    const notes = orgAuditAnnotations(event)
    expect(notes.map((n) => n.key)).toEqual(['actor', 'from', 'to'])
    expect(notes[1].value).toBe(roleById('front-desk').label)
    expect(notes[2].value).toBe(roleById('org-admin').label)
    // A member id is not a role and is passed through untouched.
    expect(notes[0].value).toBe('admin-1')
  })

  it('keeps an annotation it does not recognise instead of dropping it', () => {
    const event: OrgAuditEvent = {
      seq: 1,
      ts: 1,
      actor: 'org_admin',
      action: 'org.member_added',
      objectRef: 'ada',
      meta: { somethingNew: 'value' },
      entryHash: 'h',
    }
    expect(orgAuditAnnotations(event)).toEqual([
      { key: 'somethingNew', label: 'somethingNew', value: 'value' },
    ])
    expect(orgAuditAnnotations({ ...event, meta: null })).toEqual([])
  })

  it('names the subject of a refusal correctly, because it is not a member', () => {
    // `objectRef` on a denial is the ACTION that was refused. Labelling it "Member" would be
    // actively misleading on the one entry an administrator reads most carefully.
    expect(orgAuditSubjectLabel('org.action_denied')).toBe('Refused action')
    expect(orgAuditSubjectLabel('org.member_added')).toBe('Member')
  })

  it('carries the two caveats a served log needs, on the log', () => {
    expect(AUDIT_IS_METADATA_ONLY).toContain('not provably complete')
    expect(AUDIT_IS_METADATA_ONLY).toContain('withhold the newest entries')
    expect(AUDIT_IS_A_DIFFERENT_CHAIN).toContain('different log from any patient’s')
    const audit = codeOf('AuditPanel.svelte')
    expect(audit).toContain('AUDIT_IS_METADATA_ONLY')
    expect(audit).toContain('AUDIT_IS_A_DIFFERENT_CHAIN')
  })
})

/* ═══════════════════════════════════════════════════════════════════════════════════════════
   (e) The console is wired to the surface it describes.
   ═══════════════════════════════════════════════════════════════════════════════════════════ */

describe('(e) every screen the console names is mounted', () => {
  it('mounts the five panels behind its own navigation', () => {
    const console_ = codeOf('PracticeConsole.svelte')
    for (const panel of [
      '<PracticeSignIn',
      '<CreatePracticePanel',
      '<RosterPanel',
      '<AddMemberPanel',
      '<AuditPanel',
    ]) {
      expect(console_, panel).toContain(panel)
    }
  })

  it('holds no credential of its own and remembers nothing between visits', () => {
    // No storage of any kind on this surface: the anti-forgery token lives in memory for the life
    // of the page, and the session cookie is the browser's. A console that remembered a token would
    // be a console that could act after its reader had walked away.
    for (const file of componentFiles) {
      expect(codeOf(file), file).not.toContain('localStorage')
      expect(codeOf(file), file).not.toContain('sessionStorage')
    }
  })

  it('says on the sign-in screen that a session is not a membership', () => {
    // The standing is per practice and looked up on every request, which is why being signed in
    // and being a member of the practice you named are two different things.
    expect(SIGN_IN_IS_THE_PORTAL_CREDENTIAL).toContain('per practice on every request')
    expect(SIGN_IN_IS_THE_PORTAL_CREDENTIAL).toContain('nothing in another')
    expect(codeOf('PracticeSignIn.svelte')).toContain('SIGN_IN_IS_THE_PORTAL_CREDENTIAL')
    // One authentication path in this product, reused rather than reimplemented.
    expect(codeOf('PracticeSignIn.svelte')).toContain("from '../../therapist/session'")
  })

  it('never reports a request it did not make as a refusal by the server', () => {
    // A field the page itself rejected and a request the server refused are different events, and
    // printing the second over the first sends somebody reading server logs for something that
    // never happened. Both forms keep them in separate state and render separate headings.
    for (const file of ['AddMemberPanel.svelte', 'CreatePracticePanel.svelte']) {
      const code = codeOf(file)
      expect(code, file).toContain('notSent')
      expect(code, file).toContain('Nothing was sent')
      // The local problem is never dressed up as a server failure value.
      expect(code, file).not.toContain("kind: 'invalid'")
    }
  })

  it('lets the outcome heading say when the outcome is not known', () => {
    // A heading that reads "the member was not added" over a dropped connection is a guess about a
    // row that may well exist. Both write forms branch their heading on the same predicate the
    // client exposes for exactly this.
    for (const file of ['AddMemberPanel.svelte', 'CreatePracticePanel.svelte']) {
      const code = codeOf(file)
      expect(code, file).toContain('nothingChanged(failure)')
      expect(code, file).toContain('is not known from here')
    }
  })

  it('says whose credential creates a practice', () => {
    // The one act on this console performed by the operator rather than by a practice member, and
    // the one most likely to be misread as evidence that an administrator is also a reader.
    expect(CREATE_USES_THE_SERVER_TOKEN).toContain('provisioning token')
    expect(CREATE_USES_THE_SERVER_TOKEN).toContain('confers no clinical read')
    expect(codeOf('CreatePracticePanel.svelte')).toContain('CREATE_USES_THE_SERVER_TOKEN')
    expect(codeOf('CreatePracticePanel.svelte')).toContain('createPractice(')
  })
})
