<script lang="ts">
  /*
   * SENDING THE OWNER'S PUBLIC KEY FOR ONE RELATIONSHIP.
   *
   * The server has had this route since the therapist portal was built, and until now NOTHING ever
   * called it — which is why issue #122's silent overwrite in LoginGate had never bitten: there was
   * never a published key to overwrite anything with. This is the caller.
   *
   * The state that matters is not "did the POST succeed". It is WHAT IS PUBLISHED, because the
   * table is insert-only and a 409 covers two completely different situations: the same key already
   * there (nothing to do) and a DIFFERENT key already there (this clinician can never verify this
   * owner again). So this reads back before and after, and compares. A screen that showed the
   * status code would be showing something nobody can act on.
   */
  import { Card, Callout } from '../ui'
  import { PortalClient, relRefOf, type OwnerKeyRecord } from '../../sync/portal'
  import { toBase64 } from '../../share/sharecrypto'
  import { fingerprint } from '../../assignments/crypto'
  import type { PinnedTherapist } from './session'
  import {
    CANNOT_BE_REPLACED,
    PUBLISH_LEDE,
    PUBLISH_ACTION,
    PUBLISH_BUSY,
    ALREADY_YOURS,
    DIFFERENT_KEY_PUBLISHED,
    COULD_NOT_CHECK,
    classifyPublishedKey,
  } from '../../owner/publishKey'

  let {
    therapist,
    client,
    signPub,
    boxPub,
  }: {
    therapist: PinnedTherapist
    client: PortalClient | null
    signPub: Uint8Array
    boxPub: Uint8Array
  } = $props()

  type State = 'checking' | 'absent' | 'mine' | 'different' | 'unknown'

  let phase = $state<State>('checking')
  let published = $state<OwnerKeyRecord | null>(null)
  let busy = $state(false)
  let error = $state('')

  const mySignB64 = $derived(toBase64(signPub))
  const myBoxB64 = $derived(toBase64(boxPub))
  const myFp = $derived(fingerprint(signPub))
  const filedOn = (ms: number) => (ms ? new Date(ms).toLocaleDateString() : 'a date it did not give')

  /* The comparison lives in owner/publishKey.ts so it is a node test rather than a claim here. */
  const classify = (record: OwnerKeyRecord | null): State =>
    classifyPublishedKey(record, { signPubB64: mySignB64, boxPubB64: myBoxB64 })

  async function check() {
    if (!client) {
      phase = 'unknown'
      return
    }
    error = ''
    try {
      published = await client.publishedOwnerKeys(await relRefOf(therapist.inboxToken))
      phase = classify(published)
    } catch {
      phase = 'unknown'
      error = COULD_NOT_CHECK
    }
  }

  async function send() {
    if (!client) return
    busy = true
    error = ''
    try {
      const relRef = await relRefOf(therapist.inboxToken)
      await client.publishOwnerKeys(relRef, mySignB64, myBoxB64)
      /*
       * Read back regardless of which answer the POST gave. 'published' is not proof on its own —
       * the only thing that settles what this clinician will pin is what the server hands back.
       */
      published = await client.publishedOwnerKeys(relRef)
      phase = classify(published)
    } catch {
      error = 'That key could not be sent. Nothing has changed.'
    } finally {
      busy = false
    }
  }

  $effect(() => {
    void therapist.inboxToken
    void mySignB64
    phase = 'checking'
    void check()
  })
</script>

<Card title="Your key for this connection">
  <div class="stack">
    {#if phase === 'checking'}
      <p class="hint">Reading what is published for this connection.</p>
    {:else if phase === 'absent'}
      <p class="hint">{PUBLISH_LEDE}</p>
      <p class="fp">Your fingerprint: <code>{myFp}</code></p>
      <Callout tone="warn" title="This cannot be undone">{CANNOT_BE_REPLACED}</Callout>
      <button class="primary" onclick={send} disabled={busy || !client}>
        {busy ? PUBLISH_BUSY : PUBLISH_ACTION}
      </button>
    {:else if phase === 'mine'}
      <p class="hint">{ALREADY_YOURS}</p>
      <p class="fp">
        Your fingerprint: <code>{myFp}</code>
        <span class="faint">· on this server since {filedOn(published?.registeredAt ?? 0)}</span>
      </p>
    {:else if phase === 'different'}
      <Callout tone="critical" title="This connection is pinned to another key">
        {DIFFERENT_KEY_PUBLISHED}
      </Callout>
      <p class="fp faint">
        Published {filedOn(published?.registeredAt ?? 0)}. The key you unlocked today is
        <code>{myFp}</code>.
      </p>
    {:else}
      <p class="hint">{COULD_NOT_CHECK}</p>
      <button onclick={check} disabled={busy || !client}>Try reading it again</button>
    {/if}

    {#if error}<Callout tone="critical">{error}</Callout>{/if}
  </div>
</Card>

<style>
  .stack { display: flex; flex-direction: column; gap: var(--space-3); }
  .hint { margin: 0; color: var(--ink-soft); font-size: 0.9rem; }
  .fp { margin: 0; font-size: 0.85rem; }
  .fp code { font-family: var(--font-mono); font-size: 0.75rem; word-break: break-all; }
</style>
