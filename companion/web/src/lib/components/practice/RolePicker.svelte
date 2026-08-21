<script lang="ts">
  /*
   * CHOOSING A ROLE, WITH THE ONE FACT THAT MATTERS VISIBLE AT THE MOMENT OF CHOOSING.
   *
   * ─── WHAT THIS COMPONENT IS FOR ────────────────────────────────────────────────────────────
   *
   * docs/COMPANION_ACCESS_CONTROL.md's central claim is that "an admin can revoke anyone and see
   * who-accessed-what, yet cannot read a single clinical note", and the role catalog is where that
   * claim is made concrete: three of the roles read no clinical content at all, one reads only
   * through a separate consented grant, and none of them carries a key by title. A dropdown of six
   * words would hide every bit of that. Somebody assigning "front desk" would be making a decision
   * about what a person can see while looking at a control that says nothing about what a person
   * can see.
   *
   * So this is not a <select>. Every role is on screen at once with its own two cells from the
   * document's table — what it manages, and whether it normally reads clinical content — and the
   * selected one expands into the actions its preset actually contains.
   *
   * ─── WHY NOTHING BELOW IS TYPED INTO THIS FILE ─────────────────────────────────────────────
   *
   * Not one role label, summary or capability appears as a literal here. Every fact is read from
   * the role catalog through practice/orgRoles.ts, and orgRoles.test.ts asserts the absence — for
   * each role, that this file's markup does not contain its label or either of its summary cells.
   *
   * The reason is drift, and it is a specific kind. threePlane.test.ts enforces that no role's
   * preset contains a capability that reads somebody else's clinical content. If that invariant
   * ever broke, a hand-typed "No — control/monitoring only" under "Practice admin" would keep
   * reassuring the person doing the assigning while the test went red in a log nobody on this
   * screen is reading. Derived, the reassurance cannot outlive the fact: `clinicalReadActions` is a
   * filter over the preset, so a role that acquired a read capability renders the list of them, in
   * the alarm tone, instead of the sentence saying there are none. The interface reports what the
   * model says rather than what the model is supposed to say.
   *
   * ─── WHY THE TWO UNSEATABLE ROLES ARE SHOWN AS ABSENCES ────────────────────────────────────
   *
   * The catalog has eight roles and a practice can seat six. A picker that simply left out the
   * other two would make somebody wonder whether they had missed a scroll bar. Both absences are
   * load bearing — a practice cannot seat a person AS a patient, and cannot seat whoever runs the
   * server — so they are named, with their reasons, under the list rather than omitted from it.
   *
   * ─── COLOUR ────────────────────────────────────────────────────────────────────────────────
   *
   * The chip beside each role names its read posture, and it is neutral chrome for every posture
   * including "no clinical content". There is no reassuring tone available in this system and this
   * is exactly the place someone would want one: "reads nothing" is not good news to be marked in a
   * colour, it is one fact about a role. The only tone that ever appears here is the alarm hue, and
   * only on the drift case described above, where something is genuinely wrong.
   */
  import { Chip } from '../ui'
  import type { OrgRoleWire } from '../../practice/orgRoles'
  import { READ_POSTURE_CHIP, UNSEATABLE, seatableRoleFacts } from '../../practice/orgRoles'
  import { MEMBERSHIP_IS_NOT_READ_ACCESS } from '../../practice/copy'

  let {
    value,
    onpick,
    /** Distinguishes two pickers on one page — the add form and the roster's per-member control. */
    group,
    /** Rendered as the fieldset's legend, so each picker says which member it is about. */
    legend,
  }: {
    value: OrgRoleWire
    onpick: (role: OrgRoleWire) => void
    group: string
    legend: string
  } = $props()

  /*
   * Read once per render rather than held in state. The catalog is a module constant, so there is
   * nothing to subscribe to, and recomputing it makes it impossible for this component to be
   * showing a role model that was true when it mounted.
   */
  const roles = seatableRoleFacts()

  let selected = $derived(roles.find((r) => r.wire === value) ?? roles[0])
</script>

<fieldset class="picker">
  <legend class="legend">{legend}</legend>

  <p class="standing">{MEMBERSHIP_IS_NOT_READ_ACCESS}</p>

  <ul class="options">
    {#each roles as role (role.wire)}
      <li>
        <label class="option" class:chosen={role.wire === value}>
          <input
            type="radio"
            name={group}
            value={role.wire}
            checked={role.wire === value}
            onchange={() => onpick(role.wire)}
          />
          <span class="body">
            <span class="head">
              <span class="name">{role.label}</span>
              <Chip tone="neutral">{READ_POSTURE_CHIP[role.reads]}</Chip>
            </span>
            <span class="cells">
              <span class="cell">
                <span class="cell-label">Manages</span>
                <span class="cell-value">{role.managesSummary}</span>
              </span>
              <span class="cell">
                <span class="cell-label">Normally reads clinical content</span>
                <span class="cell-value">{role.readsSummary}</span>
              </span>
            </span>
          </span>
        </label>
      </li>
    {/each}
  </ul>

  {#if selected}
    <!--
      The selected role, opened out. `aria-live` is deliberately absent: this region changes only
      as the direct result of the reader's own click on a control they are standing on, and
      announcing it would repeat what the radio's own label already said.
    -->
    <div class="detail">
      <p class="detail-head">
        What a member holding this role may do here, and in which plane
        <span class="planes">{selected.planeLabels.join(' · ')}</span>
      </p>

      <ul class="actions">
        {#each selected.actions as action (action.id)}
          <li class="action">
            <span class="action-head">
              <span class="action-title">{action.title}</span>
              <Chip tone="neutral">{action.planeLabel}</Chip>
            </span>
            <span class="action-desc">{action.description}</span>
          </li>
        {/each}
      </ul>

      {#if selected.clinicalReadActions.length === 0}
        <p class="derived">
          None of those actions opens clinical content. Read capability is not among the things this
          role carries, and it is not among the things any role carries — it comes from a patient's
          grant, and this practice cannot mint one.
        </p>
      {:else}
        <!--
          Unreachable while threePlane.test.ts passes, and rendered anyway. If a preset ever
          acquires a read capability, this screen must say so at the moment of assignment rather
          than go on repeating the paragraph above. See the header note.
        -->
        <p class="drift">
          This role's preset contains {selected.clinicalReadActions.length}
          {selected.clinicalReadActions.length === 1 ? 'action' : 'actions'} that read another
          person's clinical content: {selected.clinicalReadActions.join(', ')}. That contradicts the
          access-control design this catalog is built from, and assigning this role should wait
          until it has been resolved.
        </p>
      {/if}
    </div>
  {/if}

  <div class="unseatable">
    <p class="unseatable-head">Two roles in the catalog cannot be seated in a practice</p>
    {#each UNSEATABLE as role (role.id)}
      <p class="unseatable-row"><span class="unseatable-name">{role.label}</span> {role.why}</p>
    {/each}
  </div>
</fieldset>

<style>
  /* A fieldset with its UA border removed rather than a div: the group needs a legend, and a legend
     outside a fieldset is not announced as one. */
  .picker {
    border: 0;
    margin: 0;
    padding: 0;
    min-width: 0;
  }

  .legend {
    padding: 0;
    font-family: var(--font-display);
    font-size: 1.05rem;
    font-weight: 560;
    color: var(--ink-text);
  }

  /* The standing correction sits above the options, not under them: it is the thing a person needs
     before they choose, and a note under a control is read after the decision it was meant to
     inform. */
  .standing {
    margin: var(--space-2) 0 var(--space-4);
    max-width: 44rem;
    color: var(--ink-soft);
    font-size: 0.9rem;
    line-height: 1.55;
  }

  .options {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }

  /* The whole row is the label, so the click target is the row rather than a 13px circle. */
  .option {
    display: flex;
    align-items: flex-start;
    gap: var(--space-3);
    padding: var(--space-3);
    border: 1px solid var(--hairline);
    border-left: 3px solid transparent;
    border-radius: var(--radius);
    background: var(--paper-sheet);
    cursor: pointer;
  }

  .option:hover {
    border-color: var(--border-strong);
  }

  /* Selection is carried by the left rule and the ground, not by hue alone — the same three-signal
     rule the nav rail follows, and the radio's own checked state is the fourth. */
  .option.chosen {
    border-left-color: var(--indigo);
    background: var(--indigo-wash);
  }

  .option input {
    margin: 0.2rem 0 0;
    flex: 0 0 auto;
  }

  .body {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    min-width: 0;
  }

  .head {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2);
  }

  .name {
    font-weight: 600;
    color: var(--ink-text);
  }

  .cells {
    display: grid;
    grid-template-columns: minmax(11rem, auto) 1fr;
    gap: var(--space-1) var(--space-4);
    min-width: 0;
  }

  .cell {
    display: contents;
  }

  /* The chrome micro-label: these are the document's own column headings, and they are field names
     rather than prose. */
  .cell-label {
    font-family: var(--font-mono);
    font-size: 10px;
    line-height: 1.6;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--chrome-soft);
  }

  .cell-value {
    color: var(--ink-soft);
    font-size: 0.875rem;
    line-height: 1.5;
    min-width: 0;
  }

  @media (max-width: 40rem) {
    .cells {
      grid-template-columns: 1fr;
      gap: 0;
    }
    .cell-value {
      margin-bottom: var(--space-2);
    }
  }

  .detail {
    margin-top: var(--space-4);
    padding: var(--space-4);
    border: 1px solid var(--hairline);
    border-radius: var(--radius);
    background: var(--paper-sheet);
  }

  .detail-head {
    display: flex;
    align-items: baseline;
    flex-wrap: wrap;
    gap: var(--space-2) var(--space-3);
    margin: 0 0 var(--space-3);
    font-weight: 600;
    color: var(--ink-text);
    font-size: 0.95rem;
  }

  .planes {
    font-family: var(--font-mono);
    font-size: 0.7rem;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--chrome-soft);
  }

  .actions {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .action {
    padding-top: var(--space-3);
    border-top: 1px solid var(--hairline);
  }

  .action:first-child {
    padding-top: 0;
    border-top: 0;
  }

  .action-head {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2);
  }

  .action-title {
    font-weight: 600;
    font-size: 0.9rem;
    color: var(--ink-text);
  }

  .action-desc {
    display: block;
    margin-top: var(--space-1);
    max-width: 44rem;
    color: var(--ink-soft);
    font-size: 0.85rem;
    line-height: 1.55;
  }

  /* Computed, not asserted — see the header. Plain ink on paper: this is a statement of fact, and
     painting a fact in a colour would make it read as a verdict. */
  .derived {
    margin: var(--space-4) 0 0;
    padding-top: var(--space-3);
    border-top: 1px solid var(--hairline);
    max-width: 44rem;
    color: var(--ink-text);
    font-size: 0.875rem;
    line-height: 1.55;
  }

  /* The single alarm hue, spent on the one condition that genuinely needs a human. */
  .drift {
    margin: var(--space-4) 0 0;
    padding: var(--space-3);
    border: 1px solid var(--clay);
    border-radius: var(--radius);
    background: var(--clay-wash);
    color: var(--ink-soft);
    font-size: 0.875rem;
    line-height: 1.55;
  }

  .unseatable {
    margin-top: var(--space-4);
    padding-top: var(--space-3);
    border-top: 1px solid var(--hairline);
  }

  .unseatable-head {
    margin: 0 0 var(--space-2);
    font-family: var(--font-mono);
    font-size: 10px;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--chrome-soft);
  }

  .unseatable-row {
    margin: 0 0 var(--space-2);
    max-width: 44rem;
    color: var(--ink-soft);
    font-size: 0.85rem;
    line-height: 1.55;
  }

  .unseatable-name {
    font-weight: 600;
    color: var(--ink-text);
  }
</style>
