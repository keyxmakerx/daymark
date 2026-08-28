<script lang="ts">
  /*
   * SIX BOXES, ONE PER GROUP — the input side of the same vocabulary the code sheet prints in.
   *
   * ─── WHY NOT ONE FIELD ────────────────────────────────────────────────────────────────────────
   *
   * A single text box would be less code and would make every diagnosis worse. The whole point of
   * the check character and the reduced alphabet is that a mistake can be reported by POSITION, and
   * the position a person can act on is "group 3, second character" rather than "symbol 12". With
   * one field, a group that is a character short silently shifts every later position, and the
   * interface either does arithmetic that is wrong or stops pointing at anything.
   *
   * Six boxes make the group structure the input's structure, so the diagnosis and the display and
   * the paper all count the same way. groups.ts explains the arithmetic; this file is the surface
   * that keeps it true.
   *
   * ─── WHAT IT DOES FOR THE PERSON TYPING ───────────────────────────────────────────────────────
   *
   *   PASTE SPREADS. Thirty characters pasted into any box fill that box and the ones after it.
   *   Without this, somebody who has the code in a file loses twenty-five characters and is then
   *   told there are problems in five groups, none of which are real. (distributeIntoGroups.)
   *
   *   IT UPPER-CASES AS YOU TYPE, because the alphabet is upper case and a person typing lower case
   *   should see their input match the paper rather than be silently corrected later.
   *
   *   IT ADVANCES ON A FULL GROUP, but only when the group has just become full by typing forward.
   *   Advancing on every keystroke that reaches five characters would make correcting the last
   *   character of a group impossible — you would fix it and be thrown into the next box.
   *
   *   IT NEVER FILTERS SILENTLY. A typed O stays an O. The temptation is to drop characters that
   *   cannot be in a code as they are typed, which feels tidy and destroys the one diagnosis this
   *   alphabet exists to give: the person needs to be TOLD that group 3 has a character that is
   *   never in a code, because that tells them their handwriting has a 0 where they read an O. A
   *   field that eats it leaves them re-reading a group that now looks fine.
   *
   * ─── AUTOCOMPLETE, MANAGERS AND KEYBOARDS ─────────────────────────────────────────────────────
   *
   * `autocomplete="off"`, `autocorrect`/`spellcheck` off, `autocapitalize="characters"`. A recovery
   * code is not a username and must never be offered back by a browser's form memory on a shared
   * machine. `type="text"` rather than `password`: the person is copying from paper and needs to
   * see what they typed to find their own mistake, and masking it would hide the very character
   * that is wrong.
   */
  import { GROUP_SIZE } from '../../recovery/recoveryCode'
  import { distributeIntoGroups, normalizeGroup } from './groups'

  let {
    groups,
    onchange,
    /** The group a current problem points at, so the box can carry the invalid state itself. */
    problemGroup = null,
    /** The id of the element holding the problem's sentence, for aria-describedby. */
    describedBy = undefined,
    disabled = false,
    /** A label for the whole set, since six boxes are one control. */
    legend,
  }: {
    groups: string[]
    onchange: (groups: string[]) => void
    problemGroup?: number | null
    describedBy?: string | undefined
    disabled?: boolean
    legend: string
  } = $props()

  /* Kept so a full group can move focus to the next box. Plain array, indexed by group. */
  let fields: HTMLInputElement[] = $state([])

  /*
   * A paste long enough to be more than one group is intercepted and spread; anything shorter is
   * left to the browser and arrives through handleInput like typing.
   *
   * WHY THE SPREAD IS HERE AND NOT IN handleInput. Doing it on every input event would make a box
   * that is already full destructive to the NEXT box: put the caret in the middle of a complete
   * group, type one character, and the six symbols would spill forward and overwrite group 4 with
   * a single character. Editing a group you have already typed is the common case when somebody is
   * checking their work, and it must not be the case that breaks.
   *
   * `event.clipboardData` is the paste's own payload — the data the person has just handed to this
   * field. Nothing here reads the system clipboard (`navigator.clipboard`), and nothing anywhere in
   * this directory writes to it; see the header note on why a copy button is deliberately absent.
   */
  function handlePaste(index: number, event: ClipboardEvent) {
    const pasted = event.clipboardData?.getData('text') ?? ''
    if (normalizeGroup(pasted).length <= GROUP_SIZE) return
    event.preventDefault()
    onchange(distributeIntoGroups(groups, index, pasted))
  }

  function handleInput(index: number, event: Event) {
    const input = event.target as HTMLInputElement
    const before = normalizeGroup(groups[index] ?? '')
    /*
     * Truncated rather than spread — see handlePaste. Nothing else is filtered: a character that
     * cannot be in a code stays exactly where it was typed, because being told "group 3 has a
     * character a code never contains" is what tells somebody their handwritten O is a 0.
     */
    const after = normalizeGroup(input.value).slice(0, GROUP_SIZE)
    const next = [...groups]
    next[index] = after
    onchange(next)
    /*
     * Advance only on the transition into a full group. Comparing against the previous length
     * distinguishes typing forward from deleting, and from re-typing the fifth character of a group
     * that was already full — both of which must leave the caret where it is.
     */
    if (after.length === GROUP_SIZE && before.length < GROUP_SIZE && index + 1 < groups.length) {
      fields[index + 1]?.focus()
    }
  }

  /*
   * Backspace at the start of an empty box moves back a group. Without it, deleting across a group
   * boundary means reaching for the mouse in the middle of correcting a mistake — which is when a
   * person is least willing to be interrupted.
   */
  function handleKey(index: number, event: KeyboardEvent) {
    if (event.key !== 'Backspace') return
    if (normalizeGroup(groups[index] ?? '').length > 0) return
    if (index === 0) return
    event.preventDefault()
    fields[index - 1]?.focus()
  }
</script>

<fieldset class="entry" {disabled}>
  <legend class="legend">{legend}</legend>
  <div class="boxes">
    {#each groups as group, i}
      <div class="box">
        <label class="u-label" for={`recovery-group-${i + 1}`}>Group {i + 1}</label>
        <input
          id={`recovery-group-${i + 1}`}
          class="field"
          type="text"
          inputmode="text"
          value={group}
          maxlength={GROUP_SIZE}
          size={GROUP_SIZE}
          autocomplete="off"
          autocapitalize="characters"
          autocorrect="off"
          spellcheck="false"
          aria-invalid={problemGroup === i + 1}
          aria-describedby={problemGroup === i + 1 ? describedBy : undefined}
          bind:this={fields[i]}
          oninput={(e) => handleInput(i, e)}
          onpaste={(e) => handlePaste(i, e)}
          onkeydown={(e) => handleKey(i, e)}
        />
      </div>
    {/each}
  </div>
</fieldset>

<style>
  .entry {
    border: 0;
    padding: 0;
    margin: 0;
    min-width: 0;
  }

  .legend {
    padding: 0;
    margin: 0 0 var(--space-2);
    font-size: 0.9rem;
    color: var(--ink-soft);
  }

  .boxes {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2) var(--space-3);
  }

  .box {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
  }

  .field {
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
    font-size: 1.15rem;
    /* Wide enough for five characters at this letter-spacing plus the caret, so a full group does
       not scroll inside its own box while it is being checked against paper. */
    inline-size: 6.5rem;
    letter-spacing: 0.14em;
    text-transform: uppercase;
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--paper-bg);
    color: var(--ink-text);
  }

  /* The clay outline is the alarm hue and the aria-invalid attribute is the same fact for a screen
     reader; the sentence beside the field is the third signal and the only one that says what to
     do. Colour is never carrying this alone. */
  .field[aria-invalid='true'] {
    border-color: var(--clay);
    background: var(--clay-wash);
  }

  .field:focus-visible {
    outline: 2px solid var(--focus-ring);
    outline-offset: 2px;
  }

  .entry[disabled] .field {
    color: var(--text-subtle);
  }

  /* An entry form on paper is furniture. The code sheet prints; the boxes it would be typed into
     do not. */
  @media print {
    .entry {
      display: none;
    }
  }
</style>
