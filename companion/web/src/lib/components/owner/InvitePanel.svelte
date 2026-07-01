<script lang="ts">
  import { PortalClient, relRefOf, type InviteResponse } from '../../sync/portal'
  import type { PinnedTherapist } from './session'

  let {
    therapist,
    client,
    smtpEnabled,
    scope,
  }: {
    therapist: PinnedTherapist
    client: PortalClient | null
    smtpEnabled: boolean
    scope: string[]
  } = $props()

  let invite = $state<InviteResponse | null>(null)
  let email = $state('')
  let busy = $state(false)
  let error = $state('')
  let copied = $state(false)

  async function mint(sendEmail: boolean) {
    if (!client) {
      error = 'No server configured.'
      return
    }
    error = ''
    busy = true
    try {
      // relRef = the hashed inbox token (the server routes/binds the invite by relRef).
      const relRef = await relRefOf(therapist.inboxToken)
      invite = await client.mintInvite(relRef, scope, undefined, sendEmail && smtpEnabled ? email : undefined)
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not mint an invite.'
    } finally {
      busy = false
    }
  }

  async function copyLink() {
    if (!invite) return
    try {
      await navigator.clipboard.writeText(invite.link)
      copied = true
      setTimeout(() => (copied = false), 2000)
    } catch {
      copied = false
    }
  }
</script>

<div class="invite card">
  <h4>Invite {therapist.displayName}</h4>
  <p class="oob">
    <strong>Out-of-band is the secure channel.</strong> The link below is a single-use, expiring
    bootstrap — it carries no secret the server can use to impersonate anyone. Deliver it in
    person or over a channel you trust, and confirm the fingerprint words:
    <span class="sas">{therapist.fingerprintWords}</span>
  </p>

  {#if !invite}
    <button class="primary" onclick={() => mint(false)} disabled={busy}>{busy ? 'Minting…' : 'Create invite link'}</button>
  {:else}
    <label class="linkbox">
      <span>Single-use invite link (expires {new Date(invite.expiresAt).toISOString()})</span>
      <input type="text" readonly value={invite.link} aria-label="Invite link" />
    </label>
    <div class="row">
      <button onclick={copyLink}>{copied ? 'Copied' : 'Copy link'}</button>
    </div>
  {/if}

  {#if smtpEnabled}
    <div class="email">
      <label>
        <span>Send by email <em>(optional convenience — link only, no records)</em></span>
        <input type="email" bind:value={email} placeholder="therapist@example.com" autocomplete="off" />
      </label>
      <button onclick={() => mint(true)} disabled={busy || !email}>Send email invite</button>
    </div>
  {/if}

  {#if error}<p class="error" role="alert">{error}</p>{/if}
</div>

<style>
  .invite { display: flex; flex-direction: column; gap: var(--space-3); }
  .oob { margin: 0; font-size: 0.85rem; color: var(--ink-soft); }
  .sas { font-family: var(--font-mono); background: var(--paper-bg); padding: 0.1rem var(--space-2); border-radius: 4px; }
  .linkbox { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  .linkbox span { color: var(--ink-soft); }
  input { font: inherit; padding: var(--space-2) var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  input[readonly] { font-family: var(--font-mono); font-size: 0.8rem; }
  .row { display: flex; gap: var(--space-2); }
  .email { display: flex; flex-direction: column; gap: var(--space-2); border-top: 1px solid var(--hairline); padding-top: var(--space-3); }
  .email label { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  .email em { color: var(--ink-faint); font-style: normal; }
  .error { color: var(--mood-1); margin: 0; font-size: 0.85rem; }
</style>
