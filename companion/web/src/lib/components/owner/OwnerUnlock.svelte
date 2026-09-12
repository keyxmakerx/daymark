<script lang="ts">
  /*
   * THE OWNER CONSOLE'S FRONT DOOR.
   *
   * It used to have a button called "Generate owner keys". Pressing it minted an X25519 + Ed25519
   * pair that lived until the tab closed, which meant the owner's signing identity was a different
   * identity every session and a clinician who had pinned it correctly could verify nothing the
   * owner signed afterwards (issue #121). The generate path is DELETED rather than hidden: there is
   * no route to a usable key that does not go through the owner's own key, because a usable key
   * from nowhere is the defect.
   *
   * What it asks for instead is the wrapped-key file the Recovery code screen saves, and one of the
   * two secrets that open it. From there owner/unlock.ts derives the identity and wipes the master.
   *
   * WHY THE FILE, EVERY VISIT. There is nowhere to keep it. components/recovery/session.ts argues
   * at length against browser storage for key material on a page the server serves, and no endpoint
   * holds the blob. So the door holds nothing between visits, and says so rather than letting
   * someone discover it by being asked again and reading that as a fault. This console already asks
   * for a bearer token by hand every visit; this is the same posture, not a new one.
   */
  import { initAssignmentCrypto, fingerprint } from '../../assignments/crypto'
  import { sasWords, publicOf, type Identity } from '../../share/pairing'
  import { emptyGrant } from '../../assignments/grant'
  import { fromBase64, toBase64 } from '../../share/sharecrypto'
  import LowerAssuranceBanner from './LowerAssuranceBanner.svelte'
  import GroupEntry from '../recovery/GroupEntry.svelte'
  import { emptyGroups, firstGroupProblem, groupsToTyped, type GroupProblem } from '../recovery/groups'
  import { HOW_ENTRY_WORKS } from '../recovery/copy'
  import {
    UNLOCK_LEDE,
    NOTHING_IS_KEPT,
    KEY_FILE_LABEL,
    KEY_FILE_HINT,
    NO_KEY_FILE_YET,
    USE_CODE_INSTEAD,
    USE_PASSPHRASE_INSTEAD,
    CODE_OPENS_THIS_SESSION,
    UNLOCK_ACTION,
    UNLOCK_BUSY,
    FINGERPRINT_IS_STABLE,
  } from '../../owner/unlockCopy'
  import { Card, Callout } from '../ui'
  import type { OwnerSession, PinnedTherapist } from './session'

  let { onunlock }: { onunlock: (session: OwnerSession) => void } = $props()

  let busy = $state(false)
  let error = $state('')

  /*
   * The owner's identity for this session. Derived, never generated; null until they unlock. The
   * master it came from is wiped inside unlock.ts and never reaches this component.
   */
  let ownerIdentity = $state<Identity | null>(null)
  let ownerFp = $state('')

  /** The chosen key file, as text. Read on selection so the unlock itself has nothing to await. */
  let keyFileText = $state('')
  let keyFileName = $state('')

  /** Which secret is being offered. The passphrase is the everyday one; the code is on paper. */
  let secretMode = $state<'passphrase' | 'recovery'>('passphrase')
  let passphrase = $state('')
  let groups = $state(emptyGroups())
  let problem = $state<GroupProblem | null>(null)

  const messageId = $props.id()

  // Add-a-pinned-therapist form (keys pasted after OOB verification).
  let tName = $state('')
  let tSignPubB64 = $state('')
  let tBoxPubB64 = $state('')
  let tInboxToken = $state('')
  const pinned: PinnedTherapist[] = []
  let pinnedView = $state<PinnedTherapist[]>([])

  async function chooseFile(event: Event) {
    error = ''
    const input = event.currentTarget as HTMLInputElement
    const file = input.files?.[0]
    if (!file) {
      keyFileText = ''
      keyFileName = ''
      return
    }
    keyFileName = file.name
    try {
      keyFileText = await file.text()
    } catch {
      keyFileText = ''
      error = 'That file could not be read from disk. Nothing has been unlocked.'
    }
  }

  function switchSecret(mode: 'passphrase' | 'recovery') {
    secretMode = mode
    error = ''
    problem = null
    // Whichever field is being left goes with it. Half a secret left in a field is a secret sitting
    // in a form for no reason.
    passphrase = ''
    groups = emptyGroups()
  }

  function updateGroups(next: string[]) {
    groups = next
    problem = null
    error = ''
  }

  async function unlock() {
    error = ''
    problem = null

    /*
     * The check symbol runs here, before anything expensive: a single mistyped character comes back
     * positioned and instantly, rather than as a decrypt failure seconds of Argon2id later that
     * could not tell a typo from the wrong code. Same order as UseCodeFlow.
     */
    if (secretMode === 'recovery') {
      const found = firstGroupProblem(groups)
      if (found) {
        problem = found
        return
      }
    }

    busy = true
    try {
      await initAssignmentCrypto()
      const { unlockOwnerIdentity, UNLOCK_FAULT_TEXT } = await import('../../owner/unlock')
      const secret = secretMode === 'passphrase' ? passphrase : groupsToTyped(groups)
      const out = await unlockOwnerIdentity(keyFileText, secret, secretMode)
      if (!out.ok) {
        error = UNLOCK_FAULT_TEXT[out.fault]
        return
      }
      ownerIdentity = out.identity
      ownerFp = fingerprint(out.identity.ed25519.publicKey)
      // Neither secret is needed again, and neither should outlive the click that used it.
      passphrase = ''
      groups = emptyGroups()
    } catch (e) {
      error = e instanceof Error ? e.message : 'That key could not be opened.'
    } finally {
      busy = false
    }
  }

  function addTherapist() {
    error = ''
    if (!tName.trim() || !tInboxToken.trim()) {
      error = 'Fill in at least the name and the inbox token.'
      return
    }
    /*
     * The keys are OPTIONAL now, because at the moment the owner sets a relationship up the
     * clinician's keys DO NOT EXIST — they are generated in the clinician's browser during
     * acceptance, after the invitation this console is about to mint. Requiring them here was the
     * circle that made the whole pairing undrivable: paste keys to reach the screen that fetches
     * keys. An entry added without them is marked pending, can mint the invitation, and receives
     * its keys through the Published-keys tab once the clinician has accepted — checked against
     * what they read aloud, exactly as a hand-pasted pair would have been.
     */
    if (!tSignPubB64.trim() && !tBoxPubB64.trim()) {
      const t: PinnedTherapist = {
        id: `pending:${tInboxToken.trim().slice(0, 8)}:${pinned.length}`,
        displayName: tName.trim(),
        signPub: new Uint8Array(0),
        boxPub: new Uint8Array(0),
        grant: emptyGrant(`pending:${tInboxToken.trim().slice(0, 8)}:${pinned.length}`),
        inboxToken: tInboxToken.trim(),
        fingerprintWords: '',
        pinnedAt: Date.now(),
        keysPending: true,
      }
      pinned.push(t)
      pinnedView = [...pinned]
      tName = ''; tSignPubB64 = ''; tBoxPubB64 = ''; tInboxToken = ''
      return
    }
    if (!tSignPubB64.trim() || !tBoxPubB64.trim()) {
      error = 'Enter both keys, or neither — half a keypair cannot be checked against anything.'
      return
    }
    try {
      const signPub = fromBase64(tSignPubB64.trim())
      const boxPub = fromBase64(tBoxPubB64.trim())
      const words = sasWords(publicOf(ownerIdentity!), { x25519Pub: boxPub, ed25519Pub: signPub }).join(' ')
      const t: PinnedTherapist = {
        id: fingerprint(signPub),
        displayName: tName.trim(),
        signPub,
        boxPub,
        grant: emptyGrant(fingerprint(signPub)),
        inboxToken: tInboxToken.trim(),
        fingerprintWords: words,
        pinnedAt: Date.now(),
      }
      pinned.push(t)
      pinnedView = [...pinned]
      tName = ''; tSignPubB64 = ''; tBoxPubB64 = ''; tInboxToken = ''
    } catch {
      error = 'Could not parse the public keys (expect URL-safe base64, no padding).'
    }
  }

  function enter() {
    if (!ownerIdentity) return
    onunlock({ ownerBox: ownerIdentity.x25519, ownerSign: ownerIdentity.ed25519, pinned: [...pinned] })
  }
</script>

<div class="unlock">
  <Card title="Owner console">
    <div class="stack">
      <LowerAssuranceBanner />

      {#if !ownerIdentity}
        <p class="hint">{UNLOCK_LEDE}</p>
        <p class="hint">{NOTHING_IS_KEPT}</p>

        <fieldset class="add">
          <legend>Your key</legend>

          <label>
            <span>{KEY_FILE_LABEL}</span>
            <input type="file" accept="application/json,.json" onchange={chooseFile} disabled={busy} />
          </label>
          {#if keyFileName}<p class="hint">Chosen: {keyFileName}</p>{/if}
          <p class="hint">{KEY_FILE_HINT}</p>
          <p class="hint">{NO_KEY_FILE_YET}</p>

          {#if secretMode === 'passphrase'}
            <label>
              <span>Passphrase</span>
              <input type="password" bind:value={passphrase} autocomplete="current-password" disabled={busy} />
            </label>
            <button type="button" class="swap" onclick={() => switchSecret('recovery')} disabled={busy}>
              {USE_CODE_INSTEAD}
            </button>
          {:else}
            <GroupEntry
              {groups}
              onchange={updateGroups}
              problemGroup={problem?.group ?? null}
              describedBy={messageId}
              disabled={busy}
              legend="The six groups of your recovery code"
            />
            <div class="message" id={messageId} role="status" aria-live="polite">
              {#if problem}{problem.message}{/if}
            </div>
            <p class="hint">{HOW_ENTRY_WORKS}</p>
            <p class="hint">{CODE_OPENS_THIS_SESSION}</p>
            <button type="button" class="swap" onclick={() => switchSecret('passphrase')} disabled={busy}>
              {USE_PASSPHRASE_INSTEAD}
            </button>
          {/if}
        </fieldset>

        <button class="primary" onclick={unlock} disabled={busy}>{busy ? UNLOCK_BUSY : UNLOCK_ACTION}</button>
      {:else}
        <p class="fp">Your owner fingerprint: <code>{ownerFp}</code></p>
        <p class="hint">{FINGERPRINT_IS_STABLE}</p>
        <p class="pub faint">Share your public keys with therapists out-of-band to pin:
          sign <code>{toBase64(ownerIdentity.ed25519.publicKey)}</code>,
          box <code>{toBase64(ownerIdentity.x25519.publicKey)}</code>
        </p>

        <fieldset class="add">
          <legend>Add a clinician</legend>
          <p class="hint">
            Only the name and the inbox token are needed to start — their keys are created in their
            browser when they accept your invitation, and arrive on the Published keys tab. Paste
            keys here only if you already hold them from an earlier exchange.
          </p>
          <label><span>Display name</span><input type="text" bind:value={tName} autocomplete="off" /></label>
          <label><span>Ed25519 public key <em>(optional — arrives when they accept)</em></span><input type="text" bind:value={tSignPubB64} autocomplete="off" /></label>
          <label><span>X25519 public key <em>(optional — arrives when they accept)</em></span><input type="text" bind:value={tBoxPubB64} autocomplete="off" /></label>
          <label><span>Inbox token (OOB)</span><input type="password" bind:value={tInboxToken} autocomplete="off" /></label>
          <button onclick={addTherapist}>Add clinician</button>
        </fieldset>

        {#if pinnedView.length > 0}
          <ul class="pinned">
            {#each pinnedView as t (t.id)}
              <li><strong>{t.displayName}</strong> · <span class="sas">{t.fingerprintWords}</span></li>
            {/each}
          </ul>
        {/if}

        <button class="primary" onclick={enter}>Enter console</button>
      {/if}

      {#if error}<Callout tone="critical">{error}</Callout>{/if}
    </div>
  </Card>
</div>

<style>
  .unlock { max-width: 40rem; }
  .stack { display: flex; flex-direction: column; gap: var(--space-3); }
  .hint { margin: 0; color: var(--ink-soft); font-size: 0.9rem; }
  .fp code, .pub code { font-family: var(--font-mono); font-size: 0.75rem; word-break: break-all; }
  .pub { margin: 0; font-size: 0.8rem; }
  .add { display: flex; flex-direction: column; gap: var(--space-2); border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-3); }
  .add legend { padding: 0 var(--space-2); color: var(--ink-soft); font-size: 0.85rem; }
  .add label { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  .add label span { color: var(--ink-soft); }
  .swap { align-self: flex-start; background: none; border: none; padding: 0; color: var(--ink-text); font: inherit; font-size: 0.85rem; text-decoration: underline; cursor: pointer; }
  .message { min-height: 1.25rem; font-size: 0.85rem; color: var(--ink-soft); }
  input { font: inherit; padding: var(--space-2) var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  .pinned { margin: 0; padding-left: var(--space-4); font-size: 0.9rem; }
  .sas { font-family: var(--font-mono); font-size: 0.75rem; }
</style>
