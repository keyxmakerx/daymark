<script lang="ts">
  /*
   * SHARED DATA view (gated on read.share). Fetches the current curated share, opens+verifies it
   * against the PINNED owner key, then renders the EXISTING Dashboard over the decrypted bundle.
   *
   * SECURITY BOUNDARY: on ANY verification failure (forged / spliced / wrong-owner / tampered /
   * expired) fetchShare THROWS and we show a refuse-to-render error — we NEVER hand a bundle to the
   * Dashboard. The bundle is curated (scores/bands/aggregates only); nothing raw ever appears.
   */
  import { fetchShare, bundleToBackupData, ShareExpiredError } from '../../therapist/shareClient'
  import type { BackupData } from '../../backup'
  import type { UnlockedContext } from '../../therapist/context'
  import Dashboard from '../Dashboard.svelte'
  import NonDiagnosticBanner from './NonDiagnosticBanner.svelte'

  let { ctx }: { ctx: UnlockedContext } = $props()

  let data = $state<BackupData | null>(null)
  let error = $state('')
  let loaded = $state(false)
  let busy = $state(false)

  async function load() {
    error = ''
    busy = true
    data = null
    try {
      const bundle = await fetchShare(ctx.client, ctx.session, ctx.keys.box, ctx.pinnedOwnerSignPub, ctx.pinnedOwnerSigningFp)
      if (!bundle) {
        error = 'No share has been published for you yet.'
      } else {
        data = bundleToBackupData(bundle)
      }
    } catch (e) {
      // Refuse to render: never expose a possibly-forged bundle.
      data = null
      error =
        e instanceof ShareExpiredError
          ? 'This share has expired. Ask for a fresh one.'
          : 'Refused to open this share — it did not verify against the pinned owner key, or it was tampered with.'
    } finally {
      busy = false
      loaded = true
    }
  }
</script>

<section class="shared">
  <NonDiagnosticBanner />
  <div class="head">
    <h3>Shared data</h3>
    <button onclick={load} disabled={busy}>{busy ? 'Opening…' : loaded ? 'Refresh' : 'Open shared data'}</button>
  </div>

  {#if data}
    <p class="prov faint">Verified against the pinned owner key <code>{ctx.pinnedOwnerSigningFp}</code>. Curated view: scores and bands only.</p>
    <Dashboard {data} />
  {:else if error}
    <p class="error" role="alert">{error}</p>
  {:else if !loaded}
    <p class="faint empty">Open the curated data this person chose to share with you.</p>
  {/if}
</section>

<style>
  .shared { display: flex; flex-direction: column; gap: var(--space-3); }
  .head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); }
  .head h3 { margin: 0; }
  .prov { margin: 0; font-size: 0.85rem; }
  .empty { margin: 0; }
  .error { color: var(--mood-1); background: var(--mood-1-wash); border: 1px solid var(--mood-1); border-radius: var(--radius-sm); padding: var(--space-3); margin: 0; }
  code { font-family: var(--font-mono); background: var(--paper-bg); padding: 0 0.25rem; border-radius: 4px; font-size: 0.8em; }
</style>
