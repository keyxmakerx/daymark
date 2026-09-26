<script lang="ts">
  import type { BackupData } from '../../backup'
  import { buildShareBundle, previewCounts, emptySelection, buildShare, ShareUnpinnedError, type ShareSelection, type ShareBundleMeta } from '../../assignments/share'
  import { fingerprint } from '../../assignments/crypto'
  import { loadPins, savePins, pinOnFirstUse } from '../../therapist/pinStore'
  import type { ShareMeta, SealedShare } from '../../share/sharecrypto'
  import { toBase64, SHARE_CONTEXT } from '../../share/sharecrypto'
  import NonDiagnosticBanner from './NonDiagnosticBanner.svelte'
  import InvitePanel from './InvitePanel.svelte'
  import type { OwnerSession, PinnedTherapist } from './session'
  import { PortalClient, relRefOf } from '../../sync/portal'
  import {
    shareRefusedBecauseEnded, SHARE_DAYS_DEFAULT, SHARE_DAYS_MAX, SHARE_DAYS_OUT_OF_RANGE, shareDays, shareEndsLine,
  } from '../../owner/sharing'
  import { sealShare } from '../../owner/sealShare'

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
  let expiryDays = $state<number | null>(SHARE_DAYS_DEFAULT)
  let busy = $state(false)
  let status = $state('')
  let error = $state('')

  const ownerFp = $derived(fingerprint(session.ownerSign.publicKey))

  const DAY_MS = 24 * 60 * 60 * 1000
  // Null while the field holds anything the server would not honour; the line then says so.
  const days = $derived(shareDays(expiryDays))
  // The date moves with the clock: read once, it would show the day before the real end on a page
  // left open past midnight. Times are absolute (epoch ms) throughout; only this display is local,
  // so time zones and daylight saving change how the date is written, never which instant it is.
  let now = $state(Date.now())
  $effect(() => {
    const id = setInterval(() => (now = Date.now()), 60_000)
    return () => clearInterval(id)
  })
  const endsOn = $derived(
    days === null
      ? null
      : new Date(now + days * DAY_MS).toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' }),
  )

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
    if (days === null) { error = `${SHARE_DAYS_OUT_OF_RANGE} Nothing was sealed or sent.`; return }
    error = ''
    status = ''
    busy = true
    try {
      const source = data
      const portal = client
      const shareId = crypto.randomUUID()
      const createdAt = Date.now()
      const expiry = createdAt + days * DAY_MS
      const recipientFp = fingerprint(therapist.boxPub)
      const ed25519Fp = fingerprint(therapist.signPub)
      const lineage = 'share'

      // WHEN each step runs is sealShare's to decide, and its node test holds it: whether the
      // clinician ended the relationship is asked before anything below is pinned, built, sealed
      // or sent, and a check that fails is not an ending (#91, #275). What is here is the work.
      const outcome = await sealShare({
        server: portal
          ? {
              relationshipEnding: async () => portal.relationshipEnding(await relRefOf(therapist.inboxToken)),
              listVersions: () => portal.listVersions(therapist.inboxToken, 'shares', lineage),
              publish: (sealed: SealedShare, version: number) =>
                portal.putBlob(therapist.inboxToken, 'shares', lineage, version, encodeSealed(sealed), {
                  'X-Share-Meta': toBase64(new TextEncoder().encode(JSON.stringify({ shareId, version, expiry, ownerSigningFp: ownerFp }))),
                }),
            }
          : null,
        // Pin gate. The pins come from storage, NOT from `therapist`: this block used to build an
        // empty PinStore and pin the same keys it was about to seal to, so buildShare compared each
        // value against itself and could not refuse anything. See ../../therapist/pinStore.ts.
        pin: () => {
          const pins = loadPins()
          if (pinOnFirstUse(pins, { x25519Pub: therapist.boxPub, ed25519Pub: therapist.signPub }) === 'pinned-now') {
            savePins(pins)
          }
          return pins
        },
        // Sealed with the version it is published as: the therapist refuses a share whose signed
        // version differs from the one the server serves it under.
        build: (version) => {
          const meta: ShareBundleMeta = { shareId, version, createdAt, ownerFp, expiry }
          return buildShareBundle(source, sel, meta)
        },
        seal: (bundle, version, pins) => {
          const shareMeta: ShareMeta = {
            context: SHARE_CONTEXT, shareId, version, recipientFp, createdAt, expiry, ownerSigningFp: ownerFp,
          }
          return buildShare(bundle, shareMeta, therapist.boxPub, session.ownerSign, ed25519Fp, pins)
        },
      })

      if (outcome.kind === 'ended') {
        error = shareRefusedBecauseEnded(therapist.displayName, new Date(outcome.endedAt).toLocaleDateString())
      } else if (outcome.kind === 'published') {
        status = `Sealed & published share v${outcome.version}.`
      } else {
        status = 'Share sealed locally (no server configured).'
      }
    } catch (e) {
      if (e instanceof ShareUnpinnedError) {
        // This one must not read like a glitch the owner should retry through: it means the keys
        // on file for this therapist are not the keys we were about to seal their journal to.
        // The pointer at the end matters: without it the only way out of a legitimate re-key was
        // clearing site data, which un-pins everyone at once and is not something to discover.
        error = `${e.message}. Nothing was sealed or sent. Check the fingerprint words with ${therapist.displayName} out of band before trying again. If they changed their keys, the Pinned keys tab shows the old and new fingerprints side by side.`
      } else {
        error = e instanceof Error ? e.message : 'Could not build the share.'
      }
    } finally {
      busy = false
    }
  }

  /** Encode a SealedShare to opaque bytes (Uint8Arrays → base64url in a small JSON envelope). */
  function encodeSealed(s: SealedShare): Uint8Array {
    const obj = {
      fmt: s.fmt, shareId: s.shareId, version: s.version, createdAt: s.createdAt, expiry: s.expiry,
      recipientFp: s.recipientFp, ownerSigningFp: s.ownerSigningFp,
      body: toBase64(s.body), wrappedCEK: toBase64(s.wrappedCEK), ownerSig: toBase64(s.ownerSig),
    }
    return new TextEncoder().encode(JSON.stringify(obj))
  }
</script>

<section class="share">
  <NonDiagnosticBanner />
  <h3>Build a share for {therapist.displayName}</h3>
  <!--
    What a share is, in the words #305 set: access, not a copy, which ends on the date set or when
    it is stopped. A report says "a copy" where it is made; this says "access" where a share is
    built (#337). The end-date line beside the days field does not repeat here, and the revoke
    caveat stays at the revoke click, not here.
  -->
  <p class="hint">
    A share is access. {therapist.displayName} can read what you choose here until the date you
    set, or until you stop it. Self-checks are reduced to scores and bands only — never raw
    answers. Your own words go only if you switch them on below, whole, never trimmed. The share
    is sealed to {therapist.displayName}'s pinned key and signed by you.
  </p>

  <fieldset class="types">
    <legend>Include</legend>
    <label><input type="checkbox" checked={sel.types.checkIns} onchange={() => toggle('checkIns')} /> Self-checks ({counts.checkIns})</label>
    <label><input type="checkbox" checked={sel.types.moods} onchange={() => toggle('moods')} /> Mood entries ({counts.moods})</label>
    <label><input type="checkbox" checked={sel.types.journal} onchange={() => toggle('journal')} /> Journal ({counts.journal})</label>
    <label><input type="checkbox" checked={sel.types.sleep} onchange={() => toggle('sleep')} /> Sleep logs ({counts.sleep})</label>
  </fieldset>

  <!--
    Off unless the person turns it on (#337, #305): the safe choice is the default, so the control
    names what turning it on does rather than nudging with "(recommended)". It covers both kinds
    of their own words the bundle can carry — mood notes and journal text — whole or not at all.
  -->
  <label class="strip">
    <input type="checkbox" checked={sel.includeOwnWords} onchange={() => (sel = { ...sel, includeOwnWords: !sel.includeOwnWords })} />
    Include my own words (mood notes and journal text)
  </label>

  <label class="expiry">
    <span>Ends after (days)</span>
    <input type="number" min="1" max={SHARE_DAYS_MAX} step="1" bind:value={expiryDays} />
  </label>
  <p class="ends">{endsOn ? shareEndsLine(endsOn) : SHARE_DAYS_OUT_OF_RANGE}</p>

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
  .ends { margin: 0; color: var(--ink-text); font-size: 0.85rem; }
  .expiry input { font: inherit; padding: var(--space-1) var(--space-2); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  .actions { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
  /* Confirmation is solid ink, never green — a green tick would be a claim this product cannot
     make. Failure is the single alarm hue. They were --mood-5 and --mood-1. */
  .ok { color: var(--ink-text); font-size: 0.85rem; }
  .error { color: var(--clay); font-size: 0.85rem; }
</style>
