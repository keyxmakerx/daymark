<script lang="ts">
  import { ALL_CAPABILITIES, type Capability, type ApplyMode, type Grant } from '../../assignments/types'
  import { setCapability, signGrant, encodeSignedGrant } from '../../assignments/grant'
  import CapabilityRow from './CapabilityRow.svelte'
  import type { OwnerSession, PinnedTherapist } from './session'
  import { PortalClient } from '../../sync/portal'

  let {
    session,
    therapist,
    client,
    onGrantChange,
  }: {
    session: OwnerSession
    therapist: PinnedTherapist
    client: PortalClient | null
    onGrantChange: (grant: Grant) => void
  } = $props()

  function cloneGrant(g: Grant): Grant {
    return JSON.parse(JSON.stringify(g)) as Grant
  }

  let draft = $state<Grant>({ therapistFingerprint: '', capabilities: {} })
  let dirty = $state(false)
  let busy = $state(false)
  let status = $state('')
  let error = $state('')

  // Seed on mount and re-seed whenever the selected therapist changes.
  let lastTherapistId = $state<string | null>(null)
  $effect(() => {
    if (therapist.id !== lastTherapistId) {
      lastTherapistId = therapist.id
      draft = cloneGrant(therapist.grant)
      dirty = false
      status = ''
      error = ''
    }
  })

  function onRowChange(cap: Capability, granted: boolean, apply: ApplyMode) {
    draft = setCapability(draft, cap, granted, apply)
    dirty = true
    status = ''
  }

  const grantedList = $derived(ALL_CAPABILITIES.filter((c) => draft.capabilities[c]?.granted))

  async function publish() {
    error = ''
    status = ''
    busy = true
    try {
      const signed = signGrant(draft, session.ownerSign)
      onGrantChange(structuredClone(draft))
      if (client) {
        // Append a new version. The therapist reads it; nobody but the owner can forge it.
        const existing = await client.listVersions(therapist.inboxToken, 'grants', 'grant')
        const nextVersion = (existing.reduce((m, v) => Math.max(m, v.version), -1)) + 1
        await client.putBlob(therapist.inboxToken, 'grants', 'grant', nextVersion, encodeSignedGrant(signed))
        status = `Published grant v${nextVersion}.`
      } else {
        status = 'Grant signed locally (no server configured).'
      }
      dirty = false
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not publish grant.'
    } finally {
      busy = false
    }
  }
</script>

<section class="grants card">
  <h3>What {therapist.displayName} can do</h3>
  <p class="hint">
    Grants are like app permissions: default OFF, you turn on exactly what this therapist may do.
    Each grant is signed by you — nobody can forge or edit it. Publishing appends a new version.
  </p>

  <div class="summary" role="group" aria-label="Currently granted">
    {#if grantedList.length === 0}
      <span class="none">Nothing granted yet.</span>
    {:else}
      {#each grantedList as cap (cap)}
        <span class="chip">{cap}{draft.capabilities[cap]?.apply === 'auto' ? ' · auto' : ''}</span>
      {/each}
    {/if}
  </div>

  <div class="list">
    {#each ALL_CAPABILITIES as cap (cap)}
      <CapabilityRow
        capability={cap}
        grant={draft.capabilities[cap] ?? { granted: false, apply: 'propose' }}
        onchange={(granted, apply) => onRowChange(cap, granted, apply)}
      />
    {/each}
  </div>

  <p class="revoke-note faint">
    Revoking (turning a grant off) stops <em>future</em> server-mediated delivery. It does not
    claw back material already delivered — a true cutoff for past data is a re-key, which is a
    separate step.
  </p>

  <div class="actions">
    <button class="primary" onclick={publish} disabled={busy || !dirty}>
      {busy ? 'Publishing…' : 'Sign & publish grant'}
    </button>
    {#if status}<span class="ok" role="status">{status}</span>{/if}
    {#if error}<span class="error" role="alert">{error}</span>{/if}
  </div>
</section>

<style>
  .grants { display: flex; flex-direction: column; gap: var(--space-3); }
  .hint { margin: 0; color: var(--ink-soft); font-size: 0.9rem; }
  .summary { display: flex; flex-wrap: wrap; gap: var(--space-2); }
  .chip { background: var(--mood-5-wash); border: 1px solid var(--hairline); border-radius: 999px; padding: 0.15rem var(--space-3); font-size: 0.75rem; font-family: var(--font-mono); }
  .none { color: var(--ink-faint); font-size: 0.85rem; }
  .list { display: flex; flex-direction: column; }
  .revoke-note { margin: 0; font-size: 0.8rem; }
  .actions { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
  .ok { color: var(--mood-5); font-size: 0.85rem; }
  .error { color: var(--mood-1); font-size: 0.85rem; }
</style>
