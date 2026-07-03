<script lang="ts">
  /*
   * Track T2 (email Option A): register/change/remove the owner's notification email and choose
   * which events it wants. Requires a connected owner-authenticated PortalClient. The server
   * no-ops the whole feature unless SMTP is configured on top of this, but registration itself
   * works regardless (matches /v1/owner/notifications' gate: sync token configured).
   */
  import type { PortalClient } from '../../sync/portal'

  let { client }: { client: PortalClient | null } = $props()

  const EVENTS: { key: string; label: string }[] = [
    { key: 'THERAPIST_ENROLLED', label: 'A therapist finishes enrolling' },
    { key: 'NEW_ASSIGNMENT', label: 'A therapist assigns something new' },
    { key: 'NEW_GAMEPLAN', label: 'A therapist publishes a new game plan' },
  ]

  let email = $state('')
  let events = $state<Set<string>>(new Set())
  let loadedFor = $state<PortalClient | null>(null)
  let busy = $state(false)
  let status = $state('')
  let error = $state('')

  async function load() {
    if (!client) return
    const forClient = client
    error = ''
    try {
      const s = await forClient.getNotificationSettings()
      email = s.email ?? ''
      events = new Set(s.events)
      loadedFor = forClient
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not load notification settings.'
    }
  }

  function toggle(key: string) {
    const next = new Set(events)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    events = next
  }

  async function save() {
    if (!client) {
      error = 'No server configured.'
      return
    }
    error = ''
    status = ''
    busy = true
    try {
      await client.setNotificationSettings(email.trim() || null, [...events])
      status = email.trim() ? 'Saved.' : 'Notifications turned off.'
    } catch (e) {
      error = e instanceof Error ? e.message : 'Could not save notification settings.'
    } finally {
      busy = false
    }
  }

  // Re-load whenever the connected client identity changes (e.g. the owner reconnects to a
  // different server) — keying off `loaded` alone would leave stale settings from the previous
  // server on screen, and risk saving them over the new server's actual settings.
  $effect(() => {
    if (client && client !== loadedFor) load()
  })
</script>

<div class="notify card">
  <h4>Notifications</h4>
  <p class="hint">
    Optional, off by default. Requires the operator to have configured outbound SMTP. Emails carry
    only an event type and a link to the portal — never record content. This also enables
    <strong>access-token recovery</strong>: if you lose your owner access token, a link to reset it
    can be sent here.
  </p>

  {#if !client}
    <p class="empty faint">Connect to a server above to manage notifications.</p>
  {:else}
    <label class="email-field">
      <span>Notification email <em>(stored in plaintext on the server; see COMPANION_SECURITY.md)</em></span>
      <input type="email" bind:value={email} placeholder="you@example.com" autocomplete="off" />
    </label>

    <fieldset class="events">
      <legend>Notify me when…</legend>
      {#each EVENTS as ev (ev.key)}
        <label class="event">
          <input type="checkbox" checked={events.has(ev.key)} onchange={() => toggle(ev.key)} />
          <span>{ev.label}</span>
        </label>
      {/each}
    </fieldset>

    <div class="row">
      <button class="primary" onclick={save} disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
      {#if status}<span class="status">{status}</span>{/if}
    </div>
  {/if}

  {#if error}<p class="error" role="alert">{error}</p>{/if}
</div>

<style>
  .notify { display: flex; flex-direction: column; gap: var(--space-3); max-width: 34rem; }
  .hint { margin: 0; font-size: 0.85rem; color: var(--ink-soft); }
  .empty { margin: 0; }
  .email-field { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; }
  .email-field em { color: var(--ink-faint); font-style: normal; }
  input[type='email'] { font: inherit; padding: var(--space-2) var(--space-3); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  .events { display: flex; flex-direction: column; gap: var(--space-2); border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-3); }
  .events legend { padding: 0 var(--space-2); color: var(--ink-soft); font-size: 0.85rem; }
  .event { display: flex; align-items: center; gap: var(--space-2); font-size: 0.85rem; }
  .row { display: flex; align-items: center; gap: var(--space-3); }
  .status { font-size: 0.8rem; color: var(--ink-soft); }
  .error { color: var(--mood-1); margin: 0; font-size: 0.85rem; }
</style>
