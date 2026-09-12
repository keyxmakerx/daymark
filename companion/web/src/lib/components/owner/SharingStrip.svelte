<script lang="ts">
  /*
   * THE STANDING SHARING NOTICE (issue #105).
   *
   * On every owner screen, always, and not dismissable — a share is not an event that happened
   * once. Somebody has standing access to real entries until the owner ends it, and a notice that
   * can be scrolled away or dismissed stops being true in the reader's memory long before it stops
   * being true on the server. Dismissal is forgetting.
   *
   * CHROME AND AN INDIGO RULE, NOT CLAY. Clay is this product's only alarm hue and means one of
   * four things: needs a human, overdue, refused, destructive. A consented, ongoing share is none
   * of them, and burning the single alarm permanently on a normal state is how an alarm stops
   * meaning anything. The word carries the meaning here; the ring glyph before it is aria-hidden
   * and decorative, and the rule is structure rather than severity.
   *
   * CLAY APPEARS EXACTLY ONCE in this feature: the confirm button that actually ends the share.
   * The strip's own button is plain, because pressing it only opens a question.
   *
   * THE CAVEAT IS THE FIRST BODY LINE OF THE CONFIRM, directly above the buttons, because that is
   * the point of the click — the one moment where "revoking does not un-send what was already
   * read" is a fact somebody can still act on rather than a footnote they read last week.
   */
  import { Callout } from '../ui'
  import { PortalClient, type RelMeta } from '../../sync/portal'
  import { REVOKE_CAVEAT } from '../../pairing/copy'
  import {
    KEEP_SHARING,
    REVOKE_ACTION,
    SHARING_LABEL,
    copiesNotRemoved,
    revokeConsequence,
    revokeTitle,
    sharingLine,
    sharingStateFrom,
  } from '../../owner/sharing'

  let {
    name,
    inboxToken,
    client,
  }: {
    /** The stored display name. Falls back inside sharingLine() rather than rendering a gap. */
    name: string
    inboxToken: string
    client: PortalClient | null
  } = $props()

  let versions = $state<RelMeta[]>([])
  let loaded = $state(false)
  let confirming = $state(false)
  let working = $state(false)
  let leftover = $state(0)
  let error = $state('')

  const sharing = $derived(sharingStateFrom(versions))
  const since = $derived(sharing.since ? new Date(sharing.since).toLocaleDateString() : null)

  async function load() {
    if (!client || !inboxToken) {
      loaded = true
      return
    }
    // A failure to read is NOT rendered as "not sharing". An unreachable server says nothing about
    // whether somebody has access, and a strip that vanished on a timeout would be the one screen
    // element whose absence a person would read as reassurance.
    try {
      versions = await client.listVersions(inboxToken, 'shares', 'share')
      error = ''
    } catch {
      error = 'This console could not check whether sharing is active. Nothing has changed.'
    } finally {
      loaded = true
    }
  }

  async function revoke() {
    if (!client) return
    working = true
    error = ''
    try {
      const result = await client.revokeShare(inboxToken)
      leftover = result.copiesNotRemoved
      versions = []
      confirming = false
    } catch {
      error = 'Sharing could not be ended. Nothing has changed.'
    } finally {
      working = false
    }
  }

  $effect(() => {
    void inboxToken
    loaded = false
    leftover = 0
    void load()
  })
</script>

{#if loaded && sharing.active}
  <aside class="strip" aria-label="Sharing">
    <div class="row">
      <span class="tag"><span class="ring" aria-hidden="true">◦</span>{SHARING_LABEL}</span>
      <p class="line">{sharingLine(name, since)}</p>
      <button type="button" class="plain" onclick={() => (confirming = true)} disabled={working}>
        {REVOKE_ACTION}
      </button>
    </div>

    {#if confirming}
      <div class="confirm" role="group" aria-label={revokeTitle(name)}>
        <p class="title">{revokeTitle(name)}</p>
        <!-- First body line, directly above the buttons. See the header. -->
        <p class="body">{REVOKE_CAVEAT}</p>
        <p class="body">{revokeConsequence(name)}</p>
        <div class="actions">
          <button type="button" class="plain" onclick={() => (confirming = false)} disabled={working}>
            {KEEP_SHARING}
          </button>
          <button type="button" class="destructive" onclick={revoke} disabled={working}>
            {REVOKE_ACTION}
          </button>
        </div>
      </div>
    {/if}
  </aside>
{:else if leftover > 0}
  <!-- The strip has collapsed, and this is the one thing that may take its place: the server
       marked the versions and could not remove every copy. Saying "withdrawn" over that would be
       telling somebody a thing was gone while it sat on a disk. -->
  <Callout tone="warn" title="Sharing ended, with copies left behind">{copiesNotRemoved(leftover)}</Callout>
{/if}

{#if error}<Callout tone="critical">{error}</Callout>{/if}

<style>
  /* Chrome ground with an indigo rule. Never clay: see the header for why an ongoing consented
     share is not one of clay's four meanings. */
  .strip {
    background: var(--chrome);
    border: 1px solid var(--chrome-hair);
    border-left: 3px solid var(--indigo);
    border-radius: var(--radius-sm);
    padding: var(--space-2) var(--space-3);
  }
  .row { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
  .tag {
    display: inline-flex;
    align-items: center;
    gap: var(--space-1);
    color: var(--chrome-soft);
    font-size: 0.7rem;
    letter-spacing: 0.08em;
  }
  .ring { font-size: 0.9rem; line-height: 1; }
  .line { margin: 0; flex: 1 1 16rem; color: var(--chrome-ink); font-size: 0.9rem; }
  .confirm {
    margin-top: var(--space-3);
    padding-top: var(--space-3);
    border-top: 1px solid var(--chrome-hair);
  }
  .title { margin: 0 0 var(--space-2); color: var(--chrome-ink); font-weight: 600; font-size: 0.95rem; }
  .body { margin: 0 0 var(--space-2); color: var(--chrome-ink); font-size: 0.9rem; }
  .actions { display: flex; gap: var(--space-2); flex-wrap: wrap; margin-top: var(--space-3); }
  button { font: inherit; padding: var(--space-1) var(--space-3); border-radius: var(--radius-sm); cursor: pointer; }
  .plain { background: transparent; border: 1px solid var(--border-strong); color: var(--ink-text); }
  /* The one clay element in this feature. */
  .destructive { background: var(--clay); border: 1px solid var(--clay); color: var(--on-accent); }
  button:disabled { opacity: 0.6; cursor: default; }
</style>
