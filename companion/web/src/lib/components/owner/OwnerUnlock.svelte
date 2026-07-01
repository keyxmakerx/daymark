<script lang="ts">
  import { initAssignmentCrypto, newBoxKeyPair, newSignKeyPair, fingerprint } from '../../assignments/crypto'
  import { sasWords, publicOf, type Identity } from '../../share/pairing'
  import { emptyGrant } from '../../assignments/grant'
  import { fromBase64, toBase64 } from '../../share/sharecrypto'
  import LowerAssuranceBanner from './LowerAssuranceBanner.svelte'
  import type { OwnerSession, PinnedTherapist } from './session'

  let { onunlock }: { onunlock: (session: OwnerSession) => void } = $props()

  let busy = $state(false)
  let error = $state('')

  // In this slice the owner box+sign keypairs are CONSUMED (custody / WebAuthn-PRF wrapping is the
  // pairing/auth slice). For a usable, self-contained console we generate an in-memory owner
  // identity here; a future slice replaces this with an unlocked, wrapped-at-rest key.
  let ownerReady = $state(false)
  let ownerIdentity = $state<Identity | null>(null)
  let ownerFp = $state('')

  // Add-a-pinned-therapist form (keys pasted after OOB verification).
  let tName = $state('')
  let tSignPubB64 = $state('')
  let tBoxPubB64 = $state('')
  let tInboxToken = $state('')
  const pinned: PinnedTherapist[] = []
  let pinnedView = $state<PinnedTherapist[]>([])

  async function generateOwner() {
    error = ''
    busy = true
    try {
      await initAssignmentCrypto()
      ownerIdentity = { x25519: newBoxKeyPair(), ed25519: newSignKeyPair() }
      ownerFp = fingerprint(ownerIdentity.ed25519.publicKey)
      ownerReady = true
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not initialize crypto.'
    } finally {
      busy = false
    }
  }

  function addTherapist() {
    error = ''
    if (!ownerIdentity) { error = 'Generate your owner keys first.'; return }
    if (!tName.trim() || !tSignPubB64.trim() || !tBoxPubB64.trim() || !tInboxToken.trim()) {
      error = 'Fill in the name, both public keys, and the inbox token.'
      return
    }
    try {
      const signPub = fromBase64(tSignPubB64.trim())
      const boxPub = fromBase64(tBoxPubB64.trim())
      const words = sasWords(publicOf(ownerIdentity), { x25519Pub: boxPub, ed25519Pub: signPub }).join(' ')
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
    if (!ownerIdentity) { error = 'Generate your owner keys first.'; return }
    onunlock({ ownerBox: ownerIdentity.x25519, ownerSign: ownerIdentity.ed25519, pinned: [...pinned] })
  }
</script>

<section class="unlock card">
  <h2>Owner console</h2>
  <LowerAssuranceBanner />

  {#if !ownerReady}
    <p class="hint">
      The owner console needs your owner keys to open sealed items and sign grants/shares. In this
      build the keys are generated in-memory for the session (custody / at-rest wrapping is a
      separate step). Generate to begin.
    </p>
    <button class="primary" onclick={generateOwner} disabled={busy}>{busy ? 'Preparing…' : 'Generate owner keys'}</button>
  {:else}
    <p class="fp">Your owner fingerprint: <code>{ownerFp}</code></p>
    <p class="pub faint">Share your public keys with therapists out-of-band to pin:
      sign <code>{toBase64(ownerIdentity!.ed25519.publicKey)}</code>,
      box <code>{toBase64(ownerIdentity!.x25519.publicKey)}</code>
    </p>

    <fieldset class="add">
      <legend>Pin a therapist (after verifying out-of-band)</legend>
      <label><span>Display name</span><input type="text" bind:value={tName} autocomplete="off" /></label>
      <label><span>Ed25519 public key (base64url)</span><input type="text" bind:value={tSignPubB64} autocomplete="off" /></label>
      <label><span>X25519 public key (base64url)</span><input type="text" bind:value={tBoxPubB64} autocomplete="off" /></label>
      <label><span>Inbox token (OOB)</span><input type="password" bind:value={tInboxToken} autocomplete="off" /></label>
      <button onclick={addTherapist}>Pin therapist</button>
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

  {#if error}<p class="error" role="alert">{error}</p>{/if}
</section>

<style>
  .unlock { display: flex; flex-direction: column; gap: var(--space-3); max-width: 40rem; }
  .hint { margin: 0; color: var(--ink-soft); font-size: 0.9rem; }
  .fp code, .pub code { font-family: var(--font-mono); font-size: 0.75rem; word-break: break-all; }
  .pub { margin: 0; font-size: 0.8rem; }
  .add { display: flex; flex-direction: column; gap: var(--space-2); border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-3); }
  .add legend { padding: 0 var(--space-2); color: var(--ink-soft); font-size: 0.85rem; }
  .add label { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  .add label span { color: var(--ink-soft); }
  input { font: inherit; padding: var(--space-2) var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  .pinned { margin: 0; padding-left: var(--space-4); font-size: 0.9rem; }
  .sas { font-family: var(--font-mono); font-size: 0.75rem; }
  .error { color: var(--mood-1); margin: 0; font-size: 0.85rem; }
</style>
