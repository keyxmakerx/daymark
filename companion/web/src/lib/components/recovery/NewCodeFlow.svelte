<script lang="ts">
  /*
   * FLOW ONE — GET A CODE: set the key up on the server with a new code, show the code once, and find
   * out whether it was written down.
   *
   * ─── THE STEPS, AND WHY THE CONFIRMATION IS THE FEATURE ───────────────────────────────────────
   *
   *   start    the sentence about what losing both costs, BEFORE anything else. docs/COMPANION_
   *            ARCHITECTURE.md §1 is explicit that the cost has to be stated where the passphrase is
   *            chosen and not in a footnote. Then a read of what the server holds (#258), with the
   *            address and token of the sync card above.
   *   setup    the server holds no locked key: the set-up form the owner console's door uses too
   *            (KeySetup.svelte). With nothing on the server it makes the key; with key parameters it
   *            locks the key the passphrase already opens, after proving the passphrase.
   *   locked   the server already holds a locked key, so there is no new code to make here.
   *            Replacing a code is not built, and says so as a placeholder.
   *   showing  the code, large, in six numbered groups, with print and download beside it. The server
   *            has taken the key, read it back and opened it before this step is reached, so the code
   *            on screen is the one attached to the key the server holds.
   *   confirm  the code is hidden and two of its groups are asked for. This step is the reason the
   *            others exist: without it they produce a code that a person may or may not have
   *            recorded, which is the same as no code at all except that it looks finished.
   *   held     where the key is now.
   *
   * ─── WHAT "SHOWN ONCE" MEANS HERE, EXACTLY ────────────────────────────────────────────────────
   *
   * The code is dropped from this component's state at the end of the confirmation, and there is no
   * path back to it — no re-show button on the last step, and nothing that could re-derive it,
   * because it was never stored and cannot be recomputed from the locked key (that is the point of
   * the locked key). Before the confirmation it can be shown again as often as somebody needs.
   *
   * The honest limit of dropping it: a JavaScript string cannot be overwritten, so setting the
   * variable to null removes this component's reference and leaves the engine to collect the
   * characters whenever it does. keyStore.ts and dataKey.ts state the same limitation about their
   * buffers, and it applies here with less mitigation available, not more. What can be promised is
   * narrower and is worth being precise about: nothing writes it anywhere, and no code path reads
   * it back.
   *
   * ─── WHAT THIS FLOW DOES NOT KEEP ─────────────────────────────────────────────────────────────
   *
   * A set-up hands back the owner's identity along with the code, because the owner console's door
   * opens with it. This screen opens nothing, so the identity's private halves are wiped the moment
   * it arrives.
   */
  import { Callout, Card } from '../ui'
  import CodeSheet from './CodeSheet.svelte'
  import WriteDownCheck from './WriteDownCheck.svelte'
  import KeySetup from './KeySetup.svelte'
  import Placeholder from './Placeholder.svelte'
  import type { KeyDocument } from '../../sync/client'
  import type { EnrolmentCheck, ServerKeyPorts } from '../../recovery/serverKey'
  import type { RecoveryCode } from '../../recovery/recoveryCode'
  import type { Identity } from '../../share/pairing'
  import {
    ALREADY_LOCKED_HERE,
    CODE_CAN_ACT_AS_YOU,
    DOWNLOAD_IS_A_PLAINTEXT_COPY,
    IF_BOTH_ARE_LOST,
    KEY_CHANGED_ON_SERVER,
    KEY_STORED_HERE,
    PLACEHOLDERS,
    PRINTING,
    READ_ACTION,
    READ_BUSY,
    READ_FAILED,
    READ_NEEDS_TOKEN,
    SHOWN_ONCE,
    TOKEN_NOT_ACCEPTED,
    WHAT_THIS_OPENS,
    WHY_NOBODY_CAN_HELP,
    WRITE_IT_ON_PAPER,
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

  type Step = 'start' | 'setup' | 'locked' | 'showing' | 'confirm' | 'held'

  let step = $state<Step>('start')
  let busy = $state(false)
  let error = $state('')
  /** A statement about what happened that is not a refusal: the server's key changed under a set-up. */
  let notice = $state('')

  /** What the server held when it was read, and the reads and writes bound to that server. */
  let held = $state.raw<KeyDocument | null>(null)
  let check = $state<EnrolmentCheck | null>(null)
  let ports = $state.raw<ServerKeyPorts | null>(null)

  /* Held only between the set-up and the end of the confirmation. See the header note. */
  let code = $state<RecoveryCode | null>(null)

  const rotation = PLACEHOLDERS.find((p) => p.id === 'rotation')!

  /** Read what the server holds, with the sync card's address and token. Nothing is written here. */
  async function read() {
    error = ''
    notice = ''
    if (!token) {
      error = READ_NEEDS_TOKEN
      return
    }
    busy = true
    try {
      /*
       * Loaded here rather than imported at the top, the same way SyncPanel loads the sync client:
       * the key's module pulls in libsodium, and a person who opened this panel to read what a
       * recovery code is should not pay for a WASM crypto library to do it.
       */
      const { SyncClient, SyncError } = await import('../../sync/client')
      const { serverKeyPorts, enrolmentCheck } = await import('../../recovery/serverKey')
      const reading = serverKeyPorts(new SyncClient(serverUrl, token))
      try {
        const doc = await reading.read()
        check = doc.kind === 'keyparams' ? await enrolmentCheck(reading) : null
        held = doc
        ports = reading
        step = doc.kind === 'wrapped' ? 'locked' : 'setup'
      } catch (e) {
        error = e instanceof SyncError && e.status === 401 ? TOKEN_NOT_ACCEPTED : READ_FAILED
      }
    } catch {
      error = READ_FAILED
    } finally {
      busy = false
    }
  }

  /**
   * The server took the key, read it back and opened it: show the code, and keep nothing else. The
   * code first, before anything else can fail — the server holds the lock it opens, and a code that
   * never reached the screen would be a recovery slot nobody holds.
   */
  async function keyStored(stored: { recoveryCode: RecoveryCode; identity: Identity }) {
    code = stored.recoveryCode
    step = 'showing'
    const { zeroizeOwnerIdentity } = await import('../../owner/identity')
    zeroizeOwnerIdentity(stored.identity)
  }

  /** The server's key changed between the read and the create: go on from what it holds now. */
  function keyMoved(now: KeyDocument, nowCheck: EnrolmentCheck | null) {
    held = now
    check = nowCheck
    notice = KEY_CHANGED_ON_SERVER
    step = now.kind === 'wrapped' ? 'locked' : 'setup'
  }

  /** Where things stand is not known here any more: start again from a read. */
  function keyLost(message: string) {
    held = null
    ports = null
    notice = ''
    error = message
    step = 'start'
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
    const text = [WHAT_THIS_OPENS, '', code.display, '', IF_BOTH_ARE_LOST, '', CODE_CAN_ACT_AS_YOU, '', SHOWN_ONCE, ''].join('\n')
    download(text, 'daymark-recovery-code.txt')
  }

  function confirmed() {
    /* The single-use property, enforced by the only means available: drop it and offer no way
       back. See the header note for the honest limit of what dropping a string achieves. */
    code = null
    step = 'held'
  }
</script>

<div class="flow">
  {#if step === 'start'}
    <Card title="Make a recovery code">
      <div class="stack">
        <p class="para">{IF_BOTH_ARE_LOST}</p>
        <p class="para muted-para">{WHY_NOBODY_CAN_HELP}</p>

        <div class="actions">
          <button type="button" class="primary" onclick={read} disabled={busy}>
            {busy ? READ_BUSY : READ_ACTION}
          </button>
        </div>

        {#if error}
          <Callout tone="critical">
            <p class="para">{error}</p>
          </Callout>
        {/if}
      </div>
    </Card>
  {/if}

  {#if step === 'setup' && held && held.kind !== 'wrapped' && ports}
    <Card title="Make a recovery code">
      <div class="stack">
        {#if notice}<p class="para" role="status">{notice}</p>{/if}
        <KeySetup {ports} {held} {check} onstored={keyStored} onmoved={keyMoved} onlost={keyLost} />
      </div>
    </Card>
  {/if}

  {#if step === 'locked'}
    <Card title="This server already holds your key">
      <div class="stack">
        {#if notice}<p class="para" role="status">{notice}</p>{/if}
        <p class="para">{ALREADY_LOCKED_HERE}</p>
        <Placeholder title={rotation.title} specifiedAt={rotation.specifiedAt}>
          <p class="para">{rotation.body}</p>
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
    <Card title="Where your key is">
      <div class="stack">
        <!--
          Deliberately not a congratulation, a tick or a statement that anything is now protected.
          The server holds two locks on one key; that is the whole of what happened, and it is what
          this step says. Reassurance on this surface is the absence of a callout.
        -->
        <p class="para">{KEY_STORED_HERE}</p>
        <p class="para">
          The code is no longer in this page. Nothing here can show it again, and nothing can
          reconstruct it from the locked key — which is exactly why a locked key can sit on a
          server that never learns anything from holding it.
        </p>
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

  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }

  /* Printing this page is one of the two ways the code gets onto paper, so the controls around the
     sheet are left off the printed copy. The sheet itself, and the sentences it carries, print. */
  @media print {
    .actions {
      display: none;
    }
  }
</style>
