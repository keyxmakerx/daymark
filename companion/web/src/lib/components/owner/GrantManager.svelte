<script lang="ts">
  import { ALL_CAPABILITIES, type Capability, type ApplyMode, type Grant } from '../../assignments/types'
  import { setCapability, signGrant, encodeSignedGrant } from '../../assignments/grant'
  import CapabilityRow from './CapabilityRow.svelte'
  import { Chip } from '../ui'
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
        <Chip tone="accent">{cap}{draft.capabilities[cap]?.apply === 'auto' ? ' · auto' : ''}</Chip>
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

  /* The granted-capability chips are now ui/Chip, tone 'accent', and their CSS is deleted.
     A capability name is a scope — a machine fact qualifying the row next to it — which is
     exactly what Chip is for, and "granted" is a selected/enabled state, which is indigo.
     The old rule washed each chip in --mood-5-wash: a permission the owner switched on was
     being drawn in the top step of that person's mood ramp. The ramp is DATA only. */

  /* Was --ink-faint, which is decorative by contract and misses AA as body text (2.37:1 on
     paper). This is real prose a reader has to be able to read, so it takes --text-subtle. */
  .none { color: var(--text-subtle); font-size: 0.85rem; }

  .list { display: flex; flex-direction: column; }
  .revoke-note { margin: 0; font-size: 0.8rem; }
  .actions { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }

  /* Confirmation is solid ink, never green. A published grant is a fact, not a reassurance,
     and there is no success token in this system (app.css, invariant 2). Was --mood-5.
     The role="status" / role="alert" split, and the words, carry this apart from the colour. */
  .ok { color: var(--ink-text); font-size: 0.85rem; }

  /* Publishing failed — the single alarm hue. Was --mood-1, the "awful" step of the ramp. */
  .error { color: var(--clay); font-size: 0.85rem; }
</style>
