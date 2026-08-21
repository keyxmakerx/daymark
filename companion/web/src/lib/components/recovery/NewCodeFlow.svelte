<script lang="ts">
  /*
   * FLOW ONE — GET A CODE: generate it, show it once, and find out whether it was written down.
   *
   * ─── THE FOUR STEPS, AND WHY THE THIRD ONE IS THE FEATURE ─────────────────────────────────────
   *
   *   start    a passphrase, and the sentence about what losing both costs, BEFORE anything is
   *            generated. docs/PLAN_2026-08-COMPANION-NEXT.md §3.11.1 is explicit that the cost has
   *            to be stated where the passphrase is chosen and not in a footnote.
   *   showing  the code, large, in six numbered groups, with print and download beside it.
   *   confirm  the code is hidden and two of its groups are asked for. This step is the reason the
   *            other three exist: everything before it is machinery, and without it the machinery
   *            produces a code that a person may or may not have recorded, which is the same as no
   *            code at all except that it looks finished.
   *   held     what now exists, what does not, and where the wrapped key went.
   *
   * ─── WHAT "SHOWN ONCE" MEANS HERE, EXACTLY ────────────────────────────────────────────────────
   *
   * The code is dropped from this component's state at the end of the confirmation, and there is no
   * path back to it — no re-show button on the last step, and nothing that could re-derive it,
   * because it was never stored and cannot be recomputed from the wrapped key (that is the point of
   * the wrapped key). Before the confirmation it can be shown again as often as somebody needs.
   *
   * The honest limit of dropping it: a JavaScript string cannot be overwritten, so setting the
   * variable to null removes this component's reference and leaves the engine to collect the
   * characters whenever it does. keyStore.ts and dataKey.ts state the same limitation about their
   * buffers, and it applies here with less mitigation available, not more. What can be promised is
   * narrower and is worth being precise about: nothing writes it anywhere, and no code path reads
   * it back.
   *
   * ─── WHY THE PASSPHRASE IS ASKED FOR AT ALL ───────────────────────────────────────────────────
   *
   * A recovery code is the SECOND way into a key. There is no first way until a passphrase wraps
   * one, and a blob with only a recovery slot would be a key whose sole opener is a piece of paper —
   * a worse position than the one this feature exists to fix. So both slots are made together, in
   * one act, which is also what createRecoverableDataKey() does and why it takes a passphrase.
   *
   * ─── THE COST, IN SECONDS, AND WHY IT IS NOT A PROGRESS BAR ───────────────────────────────────
   *
   * Generating wraps the key twice, and each wrap is a full Argon2id derivation at the 256 MiB /
   * 3-pass floor: a few seconds of a locked-up tab. The button says what is happening in words. A
   * percentage would be invented — nothing here can measure how far through a derivation it is —
   * and a spinner that implied progress it could not observe is the small version of the same lie
   * this whole surface is careful about.
   */
  import { Callout, Card } from '../ui'
  import CodeSheet from './CodeSheet.svelte'
  import WriteDownCheck from './WriteDownCheck.svelte'
  import Placeholder from './Placeholder.svelte'
  import { holdWrappedKey, releaseWrappedKey, encodeWrappedKeyFile } from './session'
  import type { RecoverableDataKey } from '../../recovery/dataKey'
  import type { RecoveryCode } from '../../recovery/recoveryCode'
  import {
    DOWNLOAD_IS_A_PLAINTEXT_COPY,
    HANDOFF_IS_A_STAND_IN,
    IF_BOTH_ARE_LOST,
    NEW_KEY_NOT_YOUR_ARCHIVE,
    PLACEHOLDERS,
    PRINTING,
    PRINT_SHEET_CAVEAT,
    SHOWN_ONCE,
    WHAT_THIS_OPENS,
    WHY_NOBODY_CAN_HELP,
    WRITE_IT_ON_PAPER,
  } from './copy'

  let { onhandoff }: { onhandoff?: () => void } = $props()

  type Step = 'start' | 'showing' | 'confirm' | 'held'

  let step = $state<Step>('start')
  let passphrase = $state('')
  let repeated = $state('')
  let busy = $state(false)
  let error = $state('')

  /* Held only between the generate and the end of the confirmation. See the header note. */
  let code = $state<RecoveryCode | null>(null)
  let blob = $state<RecoverableDataKey | null>(null)

  /** Placeholders pulled from the catalogue rather than written out again here. */
  const enrolment = PLACEHOLDERS.find((p) => p.id === 'enrolment')!
  const storage = PLACEHOLDERS.find((p) => p.id === 'storage')!

  async function generate() {
    error = ''
    if (!passphrase) {
      error = 'Enter a passphrase. It is the first of the two ways into this key.'
      return
    }
    if (passphrase !== repeated) {
      error = 'The two passphrases are different. Nothing has been generated.'
      return
    }
    busy = true
    try {
      /*
       * Loaded here rather than imported at the top, the same way SyncPanel loads the sync client:
       * dataKey.ts pulls in libsodium, and a person who opened this panel to read what a recovery
       * code is should not pay for a WASM crypto library to do it.
       */
      const { createRecoverableDataKey, zeroizeDataKey } = await import('../../recovery/dataKey')
      const made = await createRecoverableDataKey(passphrase)
      /*
       * The data key is wiped immediately. This flow has no use for it — both slots are already
       * wrapped — and a 32-byte key sitting in a component's state for the length of a confirmation
       * step is a key that outlives its purpose by several minutes.
       */
      zeroizeDataKey(made.dataKey)
      blob = made.blob
      code = made.recoveryCode
      holdWrappedKey(made.blob)
      step = 'showing'
    } catch (e) {
      error = e instanceof Error ? e.message : 'The key could not be generated on this device.'
    } finally {
      busy = false
    }
  }

  /**
   * Hand the person a file, without ever letting the bytes leave this tab.
   *
   * A blob URL and a synthetic click, which is the pattern App.svelte already uses to export a
   * built instrument. It touches no network: `connect-src 'self'` would refuse an upload anyway,
   * and nothing here attempts one.
   */
  function download(text: string, filename: string) {
    const file = new Blob([text], { type: 'text/plain' })
    const url = URL.createObjectURL(file)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
    URL.revokeObjectURL(url)
  }

  function downloadCode() {
    if (!code) return
    /* Everything the sheet says, in the order it says it. A file found in five years needs the
       context as much as a printed page does. */
    const text = [
      WHAT_THIS_OPENS,
      '',
      code.display,
      '',
      IF_BOTH_ARE_LOST,
      '',
      SHOWN_ONCE,
      '',
      PRINT_SHEET_CAVEAT,
      '',
    ].join('\n')
    download(text, 'daymark-recovery-code.txt')
  }

  function downloadWrappedKey() {
    if (!blob) return
    download(encodeWrappedKeyFile(blob), 'daymark-wrapped-key-stand-in.json')
  }

  function confirmed() {
    /* The single-use property, enforced by the only means available: drop it and offer no way
       back. See the header note for the honest limit of what dropping a string achieves. */
    code = null
    step = 'held'
  }

  function startAgain() {
    code = null
    blob = null
    releaseWrappedKey()
    passphrase = ''
    repeated = ''
    error = ''
    step = 'start'
  }
</script>

<div class="flow">
  {#if step === 'start'}
    <Card title="Make a recovery code">
      <div class="stack">
        <p class="para">{IF_BOTH_ARE_LOST}</p>
        <p class="para muted-para">{WHY_NOBODY_CAN_HELP}</p>

        <label class="field">
          <span class="label">Passphrase for this key</span>
          <input type="password" bind:value={passphrase} autocomplete="new-password" />
        </label>
        <label class="field">
          <span class="label">The same passphrase again</span>
          <input type="password" bind:value={repeated} autocomplete="new-password" />
        </label>

        <div class="actions">
          <button type="button" class="primary" onclick={generate} disabled={busy}>
            {busy ? 'Deriving the key — this takes a few seconds' : 'Generate a recovery code'}
          </button>
        </div>

        {#if error}
          <Callout tone="critical" title="Nothing was generated">
            <p class="para">{error}</p>
          </Callout>
        {/if}

        <Placeholder title={enrolment.title} specifiedAt={enrolment.specifiedAt}>
          <p class="para">{NEW_KEY_NOT_YOUR_ARCHIVE}</p>
          <p class="para">{enrolment.body}</p>
        </Placeholder>
      </div>
    </Card>
  {/if}

  {#if step === 'showing' && code}
    <Card title="Your recovery code">
      <div class="stack">
        <p class="para">{WRITE_IT_ON_PAPER}</p>

        <CodeSheet display={code.display} />

        <div class="actions">
          <button type="button" class="primary" onclick={() => (step = 'confirm')}>
            I have written it down
          </button>
          <button type="button" onclick={() => window.print()}>Print this page</button>
          <button type="button" onclick={downloadCode}>Download as a text file</button>
        </div>

        <p class="para small">{PRINTING}</p>
        <p class="para small">{DOWNLOAD_IS_A_PLAINTEXT_COPY}</p>
      </div>
    </Card>
  {/if}

  {#if step === 'confirm' && code}
    <Card title="What did you write down">
      <WriteDownCheck
        canonical={code.canonical}
        onconfirmed={confirmed}
        onshowagain={() => (step = 'showing')}
      />
    </Card>
  {/if}

  {#if step === 'held'}
    <Card title="Where the wrapped key is">
      <div class="stack">
        <!--
          Deliberately not a congratulation, a tick or a statement that anything is now protected.
          Two locked boxes exist in a browser tab; that is the whole of what happened, and it is
          what this step says. Reassurance on this surface is the absence of a callout.
        -->
        <p class="para">{HANDOFF_IS_A_STAND_IN}</p>
        <p class="para">
          The code is no longer in this page. Nothing here can show it again, and nothing can
          reconstruct it from the wrapped key — which is exactly why a wrapped key can sit on a
          server that never learns anything from holding it.
        </p>

        <div class="actions">
          {#if onhandoff}
            <button type="button" class="primary" onclick={onhandoff}>Try opening it with the code</button>
          {/if}
          <button type="button" onclick={downloadWrappedKey}>Save the wrapped key to a file</button>
          <button type="button" onclick={startAgain}>Make a different code</button>
        </div>

        <p class="para small">
          Making a different code replaces the wrapped key held in this page. The one you wrote down
          would open nothing afterwards.
        </p>

        <!--
          Repeated here, at the step where somebody would put the paper in a drawer and consider the
          job done. The panel says it above both flows; this is the moment it is most likely to be
          acted on, and a placeholder is cheap next to what believing otherwise would cost.
        -->
        <Placeholder title={storage.title} specifiedAt={storage.specifiedAt}>
          <p class="para">{storage.body}</p>
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

  .muted-para,
  .small {
    color: var(--ink-soft);
  }

  .small {
    font-size: 0.85rem;
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

  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }

  /* Printing this page is one of the two ways the code gets onto paper, so the controls around the
     sheet — buttons, passphrase fields, the actions row — are left off the printed copy. The sheet
     itself, and the sentences it carries, print. */
  @media print {
    .actions,
    .field {
      display: none;
    }
  }
</style>
