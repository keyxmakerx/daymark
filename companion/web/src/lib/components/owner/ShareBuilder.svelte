<script lang="ts">
  import type { BackupData } from '../../backup'
  import { buildShareBundle, previewCounts, emptySelection, buildShare, type ShareSelection, type ShareBundleMeta } from '../../assignments/share'
  import { fingerprint } from '../../assignments/crypto'
  import { PinStore } from '../../share/pairing'
  import type { ShareMeta, SealedShare } from '../../share/sharecrypto'
  import { toBase64 } from '../../share/sharecrypto'
  import NonDiagnosticBanner from './NonDiagnosticBanner.svelte'
  import InvitePanel from './InvitePanel.svelte'
  import type { OwnerSession, PinnedTherapist } from './session'
  import { PortalClient } from '../../sync/portal'

  let {
    session,
    therapist,
    data,
    client,
    smtpEnabled,
  }: {
    session: OwnerSession
    therapist: PinnedTherapist
    data: BackupData | null
    client: PortalClient | null
    smtpEnabled: boolean
  } = $props()

  let sel = $state<ShareSelection>(emptySelection())
  let expiryDays = $state(30)
  let busy = $state(false)
  let status = $state('')
  let error = $state('')

  const ownerFp = $derived(fingerprint(session.ownerSign.publicKey))

  const bundle = $derived(
    data
      ? buildShareBundle(data, sel, {
          shareId: 'preview', version: 0, createdAt: Date.now(), ownerFp, expiry: 0,
        })
      : null,
  )
  const counts = $derived(bundle ? previewCounts(bundle) : { checkIns: 0, moods: 0, journal: 0, sleep: 0 })

  function toggle(k: keyof ShareSelection['types']) {
    sel = { ...sel, types: { ...sel.types, [k]: !sel.types[k] } }
  }

  async function seal() {
    if (!data) { error = 'Load your own backup first (via the sync source).'; return }
    error = ''
    status = ''
    busy = true
    try {
      const shareId = crypto.randomUUID()
      const createdAt = Date.now()
      const expiry = createdAt + expiryDays * 24 * 60 * 60 * 1000
      const recipientFp = fingerprint(therapist.boxPub)

      const meta: ShareBundleMeta = { shareId, version: 0, createdAt, ownerFp, expiry }
      const finalBundle = buildShareBundle(data, sel, meta)

      // Pin gate: the console refuses to seal to an unpinned therapist.
      const pins = new PinStore()
      pins.pin({ x25519Pub: therapist.boxPub, ed25519Pub: therapist.signPub })
      const ed25519Fp = fingerprint(therapist.signPub)

      const shareMeta: ShareMeta = {
        context: 'daymark.share.v1', shareId, version: 0, recipientFp, expiry, ownerSigningFp: ownerFp,
      }
      const sealed: SealedShare = buildShare(finalBundle, shareMeta, therapist.boxPub, session.ownerSign, ed25519Fp, pins)

      if (client) {
        const lineage = 'share'
        const existing = await client.listVersions(therapist.inboxToken, 'shares', lineage).catch(() => [])
        const version = existing.reduce((m, v) => Math.max(m, v.version), -1) + 1
        const body = encodeSealed(sealed)
        await client.putBlob(therapist.inboxToken, 'shares', lineage, version, body, {
          'X-Share-Meta': toBase64(new TextEncoder().encode(JSON.stringify({ shareId, version, expiry, ownerSigningFp: ownerFp }))),
        })
        status = `Sealed & published share v${version}.`
      } else {
        status = 'Share sealed locally (no server configured).'
      }
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not build the share.'
    } finally {
      busy = false
    }
  }

  /** Encode a SealedShare to opaque bytes (Uint8Arrays → base64url in a small JSON envelope). */
  function encodeSealed(s: SealedShare): Uint8Array {
    const obj = {
      fmt: s.fmt, shareId: s.shareId, version: s.version, expiry: s.expiry,
      recipientFp: s.recipientFp, ownerSigningFp: s.ownerSigningFp,
      body: toBase64(s.body), wrappedCEK: toBase64(s.wrappedCEK), ownerSig: toBase64(s.ownerSig),
    }
    return new TextEncoder().encode(JSON.stringify(obj))
  }
</script>

<section class="share">
  <NonDiagnosticBanner />
  <h3>Build a share for {therapist.displayName}</h3>
  <p class="hint">
    Curate exactly what to share. Self-checks are reduced to scores and bands only — never raw
    answers. The bundle is sealed to {therapist.displayName}'s pinned key and signed by you.
  </p>

  <fieldset class="types">
    <legend>Include</legend>
    <label><input type="checkbox" checked={sel.types.checkIns} onchange={() => toggle('checkIns')} /> Self-checks ({counts.checkIns})</label>
    <label><input type="checkbox" checked={sel.types.moods} onchange={() => toggle('moods')} /> Mood entries ({counts.moods})</label>
    <label><input type="checkbox" checked={sel.types.journal} onchange={() => toggle('journal')} /> Journal ({counts.journal})</label>
    <label><input type="checkbox" checked={sel.types.sleep} onchange={() => toggle('sleep')} /> Sleep logs ({counts.sleep})</label>
  </fieldset>

  <label class="strip">
    <input type="checkbox" checked={sel.stripNotes} onchange={() => (sel = { ...sel, stripNotes: !sel.stripNotes })} />
    Strip free-text notes (recommended)
  </label>

  <label class="expiry">
    <span>Expires after (days)</span>
    <input type="number" min="1" max="365" bind:value={expiryDays} />
  </label>

  <div class="actions">
    <button class="primary" onclick={seal} disabled={busy || !data}>{busy ? 'Sealing…' : 'Seal & publish share'}</button>
    {#if status}<span class="ok" role="status">{status}</span>{/if}
    {#if error}<span class="error" role="alert">{error}</span>{/if}
  </div>

  <InvitePanel {therapist} {client} {smtpEnabled} scope={['read.share']} />
</section>

<style>
  .share { display: flex; flex-direction: column; gap: var(--space-3); }
  .hint { margin: 0; color: var(--ink-soft); font-size: 0.9rem; }
  .types { display: flex; flex-direction: column; gap: var(--space-2); border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-3); }
  .types legend { padding: 0 var(--space-2); color: var(--ink-soft); font-size: 0.85rem; }
  .types label, .strip { display: flex; align-items: center; gap: var(--space-2); font-size: 0.9rem; }
  .expiry { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; max-width: 12rem; }
  .expiry input { font: inherit; padding: var(--space-1) var(--space-2); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  .actions { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
  .ok { color: var(--mood-5); font-size: 0.85rem; }
  .error { color: var(--mood-1); font-size: 0.85rem; }
</style>
