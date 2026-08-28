<script lang="ts">
  /*
   * FLOW TWO — USE A CODE: enter it, open the key, set a passphrase again.
   *
   * ─── THE ONE THING THIS SCREEN MUST GET RIGHT ─────────────────────────────────────────────────
   *
   * A person reading this has lost their passphrase and is holding a piece of paper they wrote
   * years ago. Every message here is read as an answer to one question, which is whether their
   * writing still exists. So there is exactly one thing to get right: never say "that did not work"
   * when the software knows more than that.
   *
   * It usually does know more. The alphabet gave up five characters — 0, O, 1, I, L — so that
   * typing one is a definite error at a definite place rather than an ambiguity, and the entry is
   * grouped so the place can be named as "group 3, second character". Those diagnoses come out of
   * groups.ts before any key derivation runs at all, which also means they are instant: a mistyped
   * character is refused in milliseconds rather than after three seconds of Argon2id ending in a
   * shrug.
   *
   * And where it does NOT know more, it says so rather than guessing. A failed check character
   * proves a mistake exists and cannot locate it — see CHECKSUM_CANNOT_POINT — so that branch names
   * no group. Pointing at one would send somebody to rewrite a character that was right.
   *
   * ─── WHY THERE IS A "WHERE FROM" STEP AT ALL ──────────────────────────────────────────────────
   *
   * In a finished build there would not be one: a new device would fetch the wrapped key from the
   * server by itself, because a recovery that requires you to already have a file is not much of a
   * recovery. Nothing fetches anything here, because there is no route to fetch from — so this flow
   * has to be handed a wrapped key, and it says so in those words rather than showing an empty
   * screen that looks like a failure. session.ts sets out why the stand-in exists and what it is
   * careful not to be.
   *
   * ─── WHAT HAPPENS AFTER THE KEY OPENS ─────────────────────────────────────────────────────────
   *
   * A new passphrase, immediately, because a person who has just recovered has exactly one way in
   * again and it is a piece of paper. replacePassphrase() re-wraps only the passphrase slot: the
   * data key does not move, so nothing that was encrypted under it needs re-encrypting, and the
   * recovery slot is deliberately left alone so that changing a passphrase does not silently
   * invalidate the paper in somebody's filing cabinet.
   *
   * Two things are said at that point that a screen like this normally leaves out, and both are
   * corrections to what the button appears to have done: the old code still works (OLD_CODE_STILL_
   * WORKS), and for an archive enrolled from an older setup a passphrase change is not a revocation
   * until the previously published key parameters are gone (PASSPHRASE_CHANGE_IS_NOT_A_REVOCATION,
   * which migration.ts describes as the hazard that must not be papered over).
   */
  import { Callout, Card, EmptyState } from '../ui'
  import GroupEntry from './GroupEntry.svelte'
  import Placeholder from './Placeholder.svelte'
  import {
    decodeWrappedKeyFile,
    heldWrappedKey,
    holdWrappedKey,
    slotSummary,
    WRAPPED_KEY_FILE_FAULT_TEXT,
  } from './session'
  import { emptyGroups, firstGroupProblem, groupsToTyped, type GroupProblem } from './groups'
  import type { RecoverableDataKey } from '../../recovery/dataKey'
  import {
    CODE_DOES_NOT_OPEN_THIS,
    FILE_IS_A_STAND_IN,
    HOW_ENTRY_WORKS,
    NEW_PASSPHRASE_LEDE,
    NOTHING_TO_OPEN,
    NOT_A_PASSWORD_RESET,
    OLD_CODE_STILL_WORKS,
    PASSPHRASE_ADVICE,
    PASSPHRASE_CHANGE_IS_NOT_A_REVOCATION,
    PLACEHOLDERS,
    HANDOFF_IS_A_STAND_IN,
  } from './copy'

  type Step = 'entry' | 'opened' | 'rewrapped'

  let step = $state<Step>('entry')
  let groups = $state(emptyGroups())
  let busy = $state(false)

  /** A positioned diagnosis from the grouped entry, or null. */
  let problem = $state<GroupProblem | null>(null)
  /** A fault that is not about the shape of what was typed: the key did not open, the file was wrong. */
  let fault = $state('')

  /*
   * Read once, when this flow renders. The stand-in is written by the other flow, and the panel
   * remounts this component when it is switched to, so a key made next door is picked up.
   */
  let blob = $state<RecoverableDataKey | null>(heldWrappedKey())

  /*
   * The open data key, alive only between the code opening it and the new passphrase wrapping it.
   * It is the one genuinely secret thing this component ever holds; it is wiped in place the
   * moment it has been used, which is the whole of what zeroizeDataKey() promises and no more.
   */
  let dataKey: Uint8Array | null = null

  let passphrase = $state('')
  let repeated = $state('')

  const messageId = $props.id()
  const storage = PLACEHOLDERS.find((p) => p.id === 'storage')!

  const slots = $derived(blob ? slotSummary(blob) : null)

  function updateGroups(next: string[]) {
    groups = next
    /* Both complaints are about what was typed a moment ago; editing makes them stale. */
    problem = null
    fault = ''
  }

  async function loadFile(event: Event) {
    fault = ''
    const input = event.target as HTMLInputElement
    const file = input.files?.[0]
    if (!file) return
    const read = decodeWrappedKeyFile(await file.text())
    if (!read.ok) {
      fault = WRAPPED_KEY_FILE_FAULT_TEXT[read.fault]
      return
    }
    blob = read.blob
    holdWrappedKey(read.blob)
  }

  async function open() {
    fault = ''
    if (!blob) return
    /*
     * The shape of what was typed is checked here, before any import and any derivation. That
     * ordering is the difference between "there is a mistake in group 3" arriving instantly and a
     * three-second wait ending in a generic refusal — the same distinction dataKey.ts builds into
     * unwrapWithRecoveryCode() by taking a typed string rather than a parsed code.
     */
    const found = firstGroupProblem(groups)
    if (found) {
      problem = found
      return
    }
    problem = null
    busy = true
    try {
      const { unwrapWithRecoveryCode } = await import('../../recovery/dataKey')
      dataKey = await unwrapWithRecoveryCode(blob, groupsToTyped(groups))
      /* The typed code is dropped from the form as soon as it has been used. It is on paper in the
         person's hand; leaving thirty characters sitting in six visible boxes for the rest of the
         session serves nobody. */
      groups = emptyGroups()
      step = 'opened'
    } catch {
      /*
       * Deliberately not the thrown message. dataKey.ts says "that secret does not open this slot"
       * for every reason a slot fails to open, which is correct for a crypto module and unhelpful
       * to a person; CODE_DOES_NOT_OPEN_THIS says the same thing and then says what it could mean.
       */
      fault = CODE_DOES_NOT_OPEN_THIS
    } finally {
      busy = false
    }
  }

  async function setPassphrase() {
    fault = ''
    if (!blob || !dataKey) return
    if (!passphrase) {
      fault = 'Enter a passphrase. The key is open, and nothing has been re-wrapped.'
      return
    }
    if (passphrase !== repeated) {
      fault = 'The two passphrases are different. Nothing has been re-wrapped.'
      return
    }
    busy = true
    try {
      const { replacePassphrase, zeroizeDataKey } = await import('../../recovery/dataKey')
      const next = await replacePassphrase(blob, dataKey, passphrase)
      blob = next
      holdWrappedKey(next)
      zeroizeDataKey(dataKey)
      dataKey = null
      passphrase = ''
      repeated = ''
      step = 'rewrapped'
    } catch (e) {
      fault = e instanceof Error ? e.message : 'The key could not be re-wrapped on this device.'
    } finally {
      busy = false
    }
  }
</script>

<div class="flow">
  {#if !blob}
    <Card title="There is nothing here to open">
      <div class="stack">
        <EmptyState title="No wrapped key in this page">
          <p class="para">{NOTHING_TO_OPEN}</p>
        </EmptyState>

        <label class="file">
          <span class="label">Load a wrapped key saved by the other flow</span>
          <input type="file" accept="application/json,.json" onchange={loadFile} />
        </label>
        <p class="para small">{FILE_IS_A_STAND_IN}</p>

        {#if fault}
          <Callout tone="warn" title="That file was not loaded">
            <p class="para">{fault}</p>
          </Callout>
        {/if}

        <Placeholder title={storage.title} specifiedAt={storage.specifiedAt}>
          <p class="para">{storage.body}</p>
        </Placeholder>
      </div>
    </Card>
  {:else if step === 'entry'}
    <Card title="Enter your recovery code">
      <div class="stack">
        <p class="para">{NOT_A_PASSWORD_RESET}</p>
        <p class="para">{HOW_ENTRY_WORKS}</p>

        <GroupEntry
          {groups}
          onchange={updateGroups}
          problemGroup={problem?.group ?? null}
          describedBy={messageId}
          disabled={busy}
          legend="The six groups of your recovery code"
        />

        <div class="message" id={messageId} role="status" aria-live="polite">
          {#if problem}
            <!--
              The title is the positioned sentence when there is a position, and the plain fault
              sentence when there is not. `detail` carries the second half: for a character outside
              the alphabet, which characters a code never contains; for the check character, why it
              cannot say where the mistake is.
            -->
            <Callout tone="warn" title={problem.message}>
              {#if problem.detail}<p class="para">{problem.detail}</p>{/if}
            </Callout>
          {:else if fault}
            <Callout tone="warn" title="That code did not open this key">
              <p class="para">{fault}</p>
            </Callout>
          {/if}
        </div>

        <div class="actions">
          <button type="button" class="primary" onclick={open} disabled={busy}>
            {busy ? 'Deriving the key — this takes a few seconds' : 'Open the wrapped key'}
          </button>
        </div>

        {#if slots}
          <p class="para small">
            This wrapped key holds {slots.passphrase} passphrase
            {slots.passphrase === 1 ? 'copy' : 'copies'} and {slots.recovery} recovery
            {slots.recovery === 1 ? 'copy' : 'copies'} of the same key. Opening any one of them
            opens the same bytes.
          </p>
        {/if}
        <p class="para small">{HANDOFF_IS_A_STAND_IN}</p>
      </div>
    </Card>
  {:else if step === 'opened'}
    <Card title="Set a passphrase">
      <div class="stack">
        <p class="para">{NEW_PASSPHRASE_LEDE}</p>
        <p class="para small">{PASSPHRASE_ADVICE}</p>

        <label class="field">
          <span class="label">New passphrase</span>
          <input type="password" bind:value={passphrase} autocomplete="new-password" />
        </label>
        <label class="field">
          <span class="label">The same passphrase again</span>
          <input type="password" bind:value={repeated} autocomplete="new-password" />
        </label>

        <div class="actions">
          <button type="button" class="primary" onclick={setPassphrase} disabled={busy}>
            {busy ? 'Deriving the key — this takes a few seconds' : 'Wrap the key under this passphrase'}
          </button>
        </div>

        {#if fault}
          <Callout tone="critical" title="Nothing was re-wrapped">
            <p class="para">{fault}</p>
          </Callout>
        {/if}
      </div>
    </Card>
  {:else}
    <Card title="What changed, and what did not">
      <div class="stack">
        <p class="para">
          The passphrase copy of this key was replaced. The key itself did not change, so nothing
          encrypted under it needs re-encrypting.
        </p>
        <p class="para">{OLD_CODE_STILL_WORKS}</p>

        <Callout tone="warn" title="A passphrase change is not always a revocation">
          <p class="para">{PASSPHRASE_CHANGE_IS_NOT_A_REVOCATION}</p>
        </Callout>

        <Placeholder title={storage.title} specifiedAt={storage.specifiedAt}>
          <p class="para">{storage.body}</p>
          <p class="para">{HANDOFF_IS_A_STAND_IN}</p>
        </Placeholder>
      </div>
    </Card>
  {/if}
</div>

<style>
  .flow {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
  }

  .stack {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .para {
    margin: 0;
    max-width: 44rem;
    font-size: 0.9rem;
    line-height: 1.55;
    color: var(--ink-text);
  }

  .small {
    font-size: 0.85rem;
    color: var(--ink-soft);
  }

  .field,
  .file {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    max-width: 26rem;
  }

  .label {
    font-size: 0.9rem;
    color: var(--ink-soft);
  }

  .field input {
    font: inherit;
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--paper-bg);
    color: var(--ink-text);
  }

  .file input {
    font: inherit;
    font-size: 0.85rem;
    color: var(--ink-soft);
  }

  .message:empty {
    display: none;
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }

  /* A page printed from this flow would be a person's recovery in progress. Nothing on it belongs
     on paper, and the entry boxes would carry whatever was typed into them. */
  @media print {
    .flow {
      display: none;
    }
  }
</style>
