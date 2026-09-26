<script lang="ts">
  /*
   * FLOW TWO — USE A CODE: read the key from the server, open it with the code, set a passphrase again.
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
   * ─── WHERE THE KEY COMES FROM ─────────────────────────────────────────────────────────────────
   *
   * The owner's server keeps it, locked under the passphrase and under the code (#258), and this
   * flow reads it there when the code is offered, with the address and token of the sync card above.
   * So a person on a new device needs the paper and their access token, and nothing else. A server
   * that holds no locked key has no code that opens anything, and the screen says so rather than
   * showing an empty form that looks like a failure.
   *
   * ─── WHAT HAPPENS AFTER THE KEY OPENS ─────────────────────────────────────────────────────────
   *
   * A new passphrase, immediately, because a person who has just recovered has exactly one way in
   * again and it is a piece of paper. recovery/serverKey.ts replacePassphraseOnServer() locks the
   * same key under it and stores that as the next version of the key on the server, then reads it
   * back and opens it. The key does not move, so nothing encrypted under it needs encrypting again,
   * and the recovery slot is deliberately left alone so that changing a passphrase does not silently
   * invalidate the paper in somebody's filing cabinet.
   *
   * Two things are said at that point that a screen like this normally leaves out, and both are
   * corrections to what the button appears to have done: the old code still works (OLD_CODE_STILL_
   * WORKS), and the old passphrase is retired against the live server only, not against copies of it
   * made before (PASSPHRASE_CHANGE_IS_NOT_A_REVOCATION). "Your old passphrase no longer works" is the
   * reassuring sentence that is not true, and it is not said.
   */
  import { Callout, Card, EmptyState } from '../ui'
  import GroupEntry from './GroupEntry.svelte'
  import { slotSummary } from './session'
  import { emptyGroups, firstGroupProblem, groupsToTyped, type GroupProblem } from './groups'
  import type { KeyDocument } from '../../sync/client'
  import type { ServerKeyPorts } from '../../recovery/serverKey'
  import {
    CODE_DOES_NOT_OPEN_THIS,
    HOW_ENTRY_WORKS,
    NEW_PASSPHRASE_LEDE,
    NOTHING_TO_OPEN,
    NOT_A_PASSWORD_RESET,
    OLD_CODE_STILL_WORKS,
    PASSPHRASE_ADVICE,
    PASSPHRASE_CHANGE_IS_NOT_A_REVOCATION,
    PASSPHRASE_REPLACED,
    READ_FAILED,
    READ_NEEDS_TOKEN,
    REPLACE_FAILED,
    REPLACE_MOVED,
    REPLACE_UNCHECKED,
    TOKEN_NOT_ACCEPTED,
  } from './copy'

  let {
    /** The sync card's server address; blank means this page's own server. */
    serverUrl = '',
    /** The sync card's access token. */
    token = '',
  }: {
    serverUrl?: string
    token?: string
  } = $props()

  type Step = 'entry' | 'opened' | 'rewrapped'

  let step = $state<Step>('entry')
  let groups = $state(emptyGroups())
  let busy = $state(false)

  /** A positioned diagnosis from the grouped entry, or null. */
  let problem = $state<GroupProblem | null>(null)
  /** The code was well-formed and did not open the key the server holds. */
  let codeFault = $state('')
  /** Any other refusal: the server could not be read, or a new passphrase was not stored. */
  let fault = $state('')

  /**
   * What the server held when the code was last offered, and the reads and writes bound to it. Raw,
   * because it is replaced whole and never edited.
   */
  let held = $state.raw<KeyDocument | null>(null)
  let ports = $state.raw<ServerKeyPorts | null>(null)

  /*
   * The open data key, alive only between the code opening it and the new passphrase locking it.
   * It is the one genuinely secret thing this component ever holds; it is wiped in place the
   * moment it has been used, which is the whole of what zeroizeDataKey() promises and no more.
   */
  let dataKey: Uint8Array | null = null

  let passphrase = $state('')
  let repeated = $state('')

  const messageId = $props.id()

  const slots = $derived(held?.kind === 'wrapped' ? slotSummary(held.wrapped) : null)

  function updateGroups(next: string[]) {
    groups = next
    /* The complaints are about what was typed a moment ago; editing makes them stale. */
    problem = null
    codeFault = ''
    fault = ''
  }

  async function open() {
    codeFault = ''
    fault = ''
    /*
     * The shape of what was typed is checked here, before any import, any request and any
     * derivation. That ordering is the difference between "there is a mistake in group 3" arriving
     * instantly and a three-second wait ending in a generic refusal — the same distinction
     * dataKey.ts builds into unwrapWithRecoveryCode() by taking a typed string rather than a parsed
     * code.
     */
    const found = firstGroupProblem(groups)
    if (found) {
      problem = found
      return
    }
    problem = null
    if (!token) {
      fault = READ_NEEDS_TOKEN
      return
    }
    busy = true
    try {
      const { SyncClient, SyncError } = await import('../../sync/client')
      const { serverKeyPorts } = await import('../../recovery/serverKey')
      const reading = serverKeyPorts(new SyncClient(serverUrl, token))
      let doc: KeyDocument
      try {
        doc = await reading.read()
      } catch (e) {
        fault = e instanceof SyncError && e.status === 401 ? TOKEN_NOT_ACCEPTED : READ_FAILED
        return
      }
      held = doc
      ports = reading
      if (doc.kind !== 'wrapped') return
      const { unwrapWithRecoveryCode } = await import('../../recovery/dataKey')
      try {
        dataKey = await unwrapWithRecoveryCode(doc.wrapped, groupsToTyped(groups))
      } catch {
        /*
         * Deliberately not the thrown message. dataKey.ts says "that secret does not open this slot"
         * for every reason a slot fails to open, which is correct for a crypto module and unhelpful
         * to a person; CODE_DOES_NOT_OPEN_THIS says the same thing and then says what it could mean.
         */
        codeFault = CODE_DOES_NOT_OPEN_THIS
        return
      }
      /* The typed code is dropped from the form as soon as it has been used. It is on paper in the
         person's hand; leaving thirty characters sitting in six visible boxes for the rest of the
         session serves nobody. */
      groups = emptyGroups()
      step = 'opened'
    } catch {
      fault = READ_FAILED
    } finally {
      busy = false
    }
  }

  /** The open key is done with, whichever way the new passphrase went. */
  async function dropOpenKey() {
    const { zeroizeDataKey } = await import('../../recovery/dataKey')
    zeroizeDataKey(dataKey)
    dataKey = null
    passphrase = ''
    repeated = ''
  }

  async function setPassphrase() {
    fault = ''
    if (!held || held.kind !== 'wrapped' || !ports || !dataKey) return
    busy = true
    try {
      const { replacePassphraseOnServer } = await import('../../recovery/serverKey')
      const out = await replacePassphraseOnServer(ports, held, dataKey, passphrase, repeated)
      if (out.kind === 'refused') {
        fault =
          out.fault === 'noPassphrase'
            ? 'Enter a passphrase. The key is open, and nothing has been stored.'
            : 'The two passphrases are different. Nothing has been stored.'
        return
      }
      await dropOpenKey()
      if (out.kind === 'written') {
        step = 'rewrapped'
      } else if (out.kind === 'moved') {
        held = out.now
        fault = REPLACE_MOVED
        step = 'entry'
      } else {
        fault = REPLACE_UNCHECKED
        step = 'entry'
      }
    } catch {
      await dropOpenKey()
      fault = REPLACE_FAILED
      step = 'entry'
    } finally {
      busy = false
    }
  }
</script>

<div class="flow">
  {#if step === 'entry'}
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
          {:else if codeFault}
            <Callout tone="warn" title="That code did not open this key">
              <p class="para">{codeFault}</p>
            </Callout>
          {:else if fault}
            <Callout tone="warn">
              <p class="para">{fault}</p>
            </Callout>
          {/if}
        </div>

        {#if held && held.kind !== 'wrapped'}
          <!-- A server holding no locked key is an absence, drawn as one, not as a failure. -->
          <EmptyState title="No locked key on this server">
            <p class="para">{NOTHING_TO_OPEN}</p>
          </EmptyState>
        {/if}

        <div class="actions">
          <button type="button" class="primary" onclick={open} disabled={busy}>
            {busy ? 'Deriving the key — this takes a few seconds' : 'Open the key'}
          </button>
        </div>

        {#if slots}
          <p class="para small">
            The key this server holds is locked in {slots.passphrase} passphrase
            {slots.passphrase === 1 ? 'copy' : 'copies'} and {slots.recovery} recovery
            {slots.recovery === 1 ? 'copy' : 'copies'}. Opening any one of them opens the same
            key.
          </p>
        {/if}
      </div>
    </Card>
  {:else if step === 'opened'}
    <Card title="Set a passphrase">
      <div class="stack">
        <p class="para">{NEW_PASSPHRASE_LEDE}</p>
        <p class="para small">{PASSPHRASE_ADVICE}</p>

        <label class="field">
          <span class="label">New passphrase</span>
          <input type="password" bind:value={passphrase} autocomplete="new-password" disabled={busy} />
        </label>
        <label class="field">
          <span class="label">The same passphrase again</span>
          <input type="password" bind:value={repeated} autocomplete="new-password" disabled={busy} />
        </label>

        <div class="actions">
          <button type="button" class="primary" onclick={setPassphrase} disabled={busy}>
            {busy ? 'Locking and storing the key — this takes a few seconds' : 'Lock the key under this passphrase'}
          </button>
        </div>

        {#if fault}
          <Callout tone="critical" title="Nothing was stored">
            <p class="para">{fault}</p>
          </Callout>
        {/if}
      </div>
    </Card>
  {:else}
    <Card title="What changed, and what did not">
      <div class="stack">
        <p class="para">{PASSPHRASE_REPLACED}</p>
        <p class="para">{OLD_CODE_STILL_WORKS}</p>

        <Callout tone="warn" title="What the old passphrase still opens">
          <p class="para">{PASSPHRASE_CHANGE_IS_NOT_A_REVOCATION}</p>
        </Callout>
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

  .field {
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
