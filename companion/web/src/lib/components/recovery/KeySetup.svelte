<script lang="ts">
  /*
   * SETTING THE OWNER'S KEY UP ON A SERVER THAT HOLDS NO LOCKED KEY YET (#258).
   *
   * One form for the two places that do it — the owner console's door, and "Get a code" on the
   * Recovery code screen — so that a key is made or enrolled the same way, with the same words,
   * wherever a person happens to start. What the server held when it was read decides which:
   *
   *   nothing          a first run: a passphrase chosen here, typed twice, and a new key;
   *   key parameters   an enrolment: the passphrase the archive was written under, proved on the
   *                    newest stored snapshot (or typed twice where there is none), and the key it
   *                    already derives.
   *
   * The order of each — the proof before anything is written, the create named against what was
   * read, the read-back opened before an identity exists — is recovery/serverKey.ts's, where it is a
   * node test. This component asks, shows what came back, and forgets what was typed on every way
   * out. It shows no code: the caller does, once, because only the caller knows what follows it.
   */
  import { Callout } from '../ui'
  import type { KeyDocument } from '../../sync/client'
  import type { EnrolmentCheck, ServerKeyPorts } from '../../recovery/serverKey'
  import type { RecoveryCode } from '../../recovery/recoveryCode'
  import type { Identity } from '../../share/pairing'
  import {
    ENROL_ACTION,
    ENROL_ASKS_TWICE,
    ENROL_BUSY,
    ENROL_TRIES_NEWEST_SNAPSHOT,
    FIRST_RUN_ACTION,
    FIRST_RUN_BUSY,
    HOLDS_KEY_PARAMETERS,
    HOLDS_NOTHING,
    IF_BOTH_ARE_LOST,
    NEW_PASSPHRASE_LABEL,
    READ_BACK_DID_NOT_MATCH,
    SAME_AGAIN_LABEL,
    SETUP_FAILED,
    SETUP_FAULT_TEXT,
    SYNC_PASSPHRASE_LABEL,
  } from './copy'

  let {
    ports,
    held,
    check,
    onstored,
    onmoved,
    onlost,
    onbusy,
  }: {
    ports: ServerKeyPorts
    /** What the server held when it was read: nothing, or key parameters only. */
    held: Exclude<KeyDocument, { kind: 'wrapped' }>
    /** For key parameters: how the passphrase will be proved, as read with them. */
    check: EnrolmentCheck | null
    /** The server took the key, read it back and opened it. The code is for the caller to show once. */
    onstored: (stored: { recoveryCode: RecoveryCode; identity: Identity }) => void
    /** The server's state moved after it was read (412): what it holds now. Nothing was stored. */
    onmoved: (now: KeyDocument, check: EnrolmentCheck | null) => void
    /** Where things stand is not known here any more; the caller reads the server again. */
    onlost: (message: string) => void
    onbusy?: (busy: boolean) => void
  } = $props()

  let passphrase = $state('')
  let repeated = $state('')
  let busy = $state(false)
  let error = $state('')
  /** Set when set-up answered that the passphrase has to be typed twice after all. */
  let twiceAfterAll = $state(false)

  const firstRun = $derived(held.kind === 'none')
  const asksTwice = $derived(firstRun || check === 'typedTwice' || twiceAfterAll)

  function forgetTypedPassphrases() {
    passphrase = ''
    repeated = ''
  }

  function setBusy(b: boolean) {
    busy = b
    onbusy?.(b)
  }

  async function setUpKey() {
    error = ''
    setBusy(true)
    try {
      const { setUp, enrolmentCheck } = await import('../../recovery/serverKey')
      const out = await setUp(ports, held, passphrase, asksTwice ? repeated : null)
      if (out.kind === 'stored') {
        forgetTypedPassphrases()
        onstored({ recoveryCode: out.recoveryCode, identity: out.identity })
      } else if (out.kind === 'moved') {
        forgetTypedPassphrases()
        onmoved(out.now, out.now.kind === 'keyparams' ? await enrolmentCheck(ports) : null)
      } else if (out.kind === 'refused') {
        if (out.fault === 'typeItTwice') twiceAfterAll = true
        error = SETUP_FAULT_TEXT[out.fault]
      } else {
        forgetTypedPassphrases()
        onlost(READ_BACK_DID_NOT_MATCH)
      }
    } catch {
      forgetTypedPassphrases()
      onlost(SETUP_FAILED)
    } finally {
      setBusy(false)
    }
  }
</script>

<div class="setup">
  <fieldset class="fields">
    <legend>Your key</legend>
    <!--
      Where the passphrase is chosen, or the code about to be made, and not in a footnote
      (docs/COMPANION_ARCHITECTURE.md §1): the cost of losing both, before either is committed to.
    -->
    <p class="cost">{IF_BOTH_ARE_LOST}</p>
    {#if firstRun}
      <p class="hint">{HOLDS_NOTHING}</p>
    {:else}
      <p class="hint">{HOLDS_KEY_PARAMETERS}</p>
      <p class="hint">{asksTwice ? ENROL_ASKS_TWICE : ENROL_TRIES_NEWEST_SNAPSHOT}</p>
    {/if}
    <label>
      <span>{firstRun ? NEW_PASSPHRASE_LABEL : SYNC_PASSPHRASE_LABEL}</span>
      <input
        type="password"
        bind:value={passphrase}
        autocomplete={firstRun ? 'new-password' : 'current-password'}
        disabled={busy}
      />
    </label>
    {#if asksTwice}
      <label>
        <span>{SAME_AGAIN_LABEL}</span>
        <input type="password" bind:value={repeated} autocomplete="off" disabled={busy} />
      </label>
    {/if}
  </fieldset>

  <button type="button" class="primary" onclick={setUpKey} disabled={busy}>
    {#if firstRun}{busy ? FIRST_RUN_BUSY : FIRST_RUN_ACTION}{:else}{busy ? ENROL_BUSY : ENROL_ACTION}{/if}
  </button>

  {#if error}
    <Callout tone="critical">
      <p class="para">{error}</p>
    </Callout>
  {/if}
</div>

<style>
  .setup {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .fields {
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    border: 1px solid var(--hairline);
    border-radius: var(--radius-sm);
    padding: var(--space-3);
  }

  .fields legend {
    padding: 0 var(--space-2);
    color: var(--ink-soft);
    font-size: 0.85rem;
  }

  .hint,
  .para,
  .cost {
    margin: 0;
    max-width: 44rem;
    font-size: 0.9rem;
    line-height: 1.55;
  }

  .cost {
    color: var(--ink-text);
  }

  .hint {
    color: var(--ink-soft);
  }

  label {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    max-width: 26rem;
    font-size: 0.85rem;
  }

  label span {
    color: var(--ink-soft);
  }

  input {
    font: inherit;
    padding: var(--space-2) var(--space-3);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-sm);
    background: var(--paper-bg);
    color: var(--ink-text);
  }

  button {
    align-self: flex-start;
  }

  /* A set-up printed halfway would carry nothing a person needs on paper. */
  @media print {
    .setup {
      display: none;
    }
  }
</style>
