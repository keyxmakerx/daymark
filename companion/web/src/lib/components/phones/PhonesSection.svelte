<script lang="ts">
  /*
   * PHONES (#431): pair a phone with this server, and see and disconnect the phones already paired.
   *
   * At the foot of the "Connect to your sync server" card, and drawn only once that card has proved a
   * server address and access token in this visit: the connection is App.svelte's, the same one the
   * "Recover access" card reuses, and nothing here asks for the token again. Phones pair on every
   * kind of server, and a solo server has no owner console, so this is the one place they pair.
   *
   * THIS FILE RENDERS AND KEEPS TIME, AND DECIDES NOTHING. Every state, every transition and every
   * word is lib/phones/ceremony.ts and lib/phones/copy.ts, where each is a node test. What is left
   * here is the clock (once a second while a code or a confirm window is on screen), the poll (about
   * every two seconds while a code is on screen, stopped by a discard, a lapse or leaving), and
   * dropping any answer that arrives after the person has already moved on.
   *
   * MOTION: NONE. No bar, pulse or spinner; the countdown's digits change once a second in a
   * fixed-width slot, the live region stays silent, and one polite line is announced at thirty
   * seconds left. A request in flight shows as its button refusing a second press.
   */
  import { untrack } from 'svelte'
  import type { OwnerConnection } from '../../owner/recoveryEmail'
  import { devicesApi } from '../../phones/devices'
  import {
    POLL_EVERY_MS,
    back,
    discard,
    expire,
    initialState,
    loadList,
    mint,
    pairThisPhone,
    phonesView,
    poll,
    tryAgain,
    wordsDontMatch,
    type ActionId,
    type PhonesPorts,
    type PhonesState,
  } from '../../phones/ceremony'

  let {
    /** The server address and access token the sync card proved in this visit, or null. */
    connection = null,
  }: {
    connection?: OwnerConnection | null
  } = $props()

  let phones = $state.raw<PhonesState>(initialState(false))
  let now = $state(Date.now())
  /** A person's action is in flight: its buttons refuse a second press until it answers. */
  let busy = $state(false)

  /** Bumped by every change of state, so an answer meant for an earlier state is dropped. */
  let generation = 0
  let ports: PhonesPorts | null = null

  const view = $derived(phonesView(phones, now))
  const uid = $props.id()

  function portsFor(c: OwnerConnection): PhonesPorts {
    return {
      api: devicesApi(c.serverUrl, c.token),
      // Loaded on demand, as the sync client is: the words need libsodium.
      words: async (key) => (await import('../../phones/words')).deviceWords(key),
      keyIdOf: async (key) => (await import('../../phones/words')).deviceKeyId(key),
      now: () => Date.now(),
    }
  }

  function set(next: PhonesState) {
    if (next === phones) return
    generation++
    phones = next
  }

  /** A transition that asks the server; its answer lands only if nothing else changed meanwhile. */
  async function ask(step: (p: PhonesPorts, s: PhonesState) => Promise<PhonesState>, byPerson: boolean) {
    const p = ports
    if (!p || (byPerson && busy)) return
    const asked = generation
    if (byPerson) busy = true
    try {
      const next = await step(p, phones)
      if (asked === generation && p === ports) set(next)
    } finally {
      if (byPerson) busy = false
    }
  }

  const act = (step: (p: PhonesPorts, s: PhonesState) => Promise<PhonesState>) => void ask(step, true)

  function onAction(id: ActionId) {
    if (id === 'back') set(back(phones))
    else if (id === 'try-again') act(tryAgain)
    else act(mint)
  }

  // A new connection starts the section again, and reads its phones.
  $effect(() => {
    const c = connection
    untrack(() => {
      generation++
      busy = false
      ports = c ? portsFor(c) : null
      phones = initialState(c !== null)
      if (c) void ask(loadList, false)
    })
  })

  const codeOnScreen = $derived(phones.pairing.step === 'waiting' ? phones.pairing.codeId : null)
  const clockRunning = $derived(phones.pairing.step === 'waiting' || phones.pairing.step === 'compare')

  // The clock, once a second, while a code or a confirm window is on screen.
  $effect(() => {
    if (!clockRunning) return
    now = Date.now()
    const tick = setInterval(() => {
      now = Date.now()
      set(expire(phones, now))
    }, 1000)
    return () => clearInterval(tick)
  })

  // The poll, while a code is on screen: one request at a time, the next one after the last answered.
  $effect(() => {
    if (codeOnScreen === null) return
    let stopped = false
    let timer: ReturnType<typeof setTimeout> | undefined
    const next = () => {
      timer = setTimeout(async () => {
        if (stopped) return
        await ask(poll, false)
        if (!stopped) next()
      }, POLL_EVERY_MS)
    }
    next()
    return () => {
      stopped = true
      clearTimeout(timer)
    }
  })
</script>

<section class="phones" aria-labelledby="{uid}-heading">
  <h3 id="{uid}-heading">{view.heading}</h3>
  <p class="lede">{view.lede}</p>

  {#if view.notice}
    <!-- Done: one past-tense sentence, in solid ink, for the rest of this visit. -->
    <p class="notice">{view.notice}</p>
  {/if}

  {#if view.pairButton}
    <button type="button" class="pair" onclick={() => act(mint)} disabled={busy}>{view.pairButton}</button>
  {/if}

  {#if view.area}
    {@const area = view.area}
    <!-- Pairing opens in place, below where the button stands. -->
    <div class="area">
      {#if area.kind === 'code'}
        <p class="para">{area.lede}</p>
        <dl class="facts">
          <dt>{area.addressLabel}</dt>
          <dd class="address">{area.address}</dd>
          <dt>{area.codeLabel}</dt>
          <dd class="code">{area.code}</dd>
        </dl>
        <p class="time" aria-live="off"><span class="clock">{area.timeLeft.clock}</span>{area.timeLeft.after}</p>
        <p class="para">{area.waiting}</p>
        <div class="actions">
          <button type="button" onclick={() => set(discard(phones))}>{area.discard}</button>
        </div>
      {:else if area.kind === 'compare'}
        <p class="para">{area.lede}</p>
        <ol class="words">
          {#each area.words as word, i (i)}
            <li><span class="n">{i + 1}</span> {word}</li>
          {/each}
        </ol>
        <p class="para" aria-live="off">{area.within.before}<span class="clock">{area.within.clock}</span>{area.within.after}</p>
        <div class="actions">
          <button type="button" class="indigo" onclick={() => act(pairThisPhone)} disabled={busy}>{area.pair}</button>
          <button type="button" onclick={() => set(wordsDontMatch(phones))} disabled={busy}>{area.mismatch}</button>
        </div>
      {:else}
        <p class="para ended">{area.sentence}</p>
        <div class="actions">
          {#each area.actions as action (action.id)}
            <button type="button" onclick={() => onAction(action.id)} disabled={busy}>{action.label}</button>
          {/each}
        </div>
      {/if}
    </div>
  {/if}

  <!-- Silent until thirty seconds are left on a code, then that one line, once. -->
  <p class="visually-hidden" aria-live="polite">{view.announcement}</p>
</section>

<style>
  .phones {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-3);
    margin-top: var(--space-3);
    padding-top: var(--space-4);
    border-top: 1px solid var(--hairline);
  }
  h3 { margin: 0; font-size: 1.05rem; color: var(--ink-text); }
  .lede { margin: 0; font-size: 0.9rem; line-height: 1.55; color: var(--ink-soft); }
  .notice { margin: 0; font-size: 0.9rem; color: var(--ink-text); }

  .area { display: flex; flex-direction: column; gap: var(--space-3); align-self: stretch; }
  .para { margin: 0; font-size: 0.9rem; line-height: 1.55; color: var(--ink-text); }
  .actions { display: flex; flex-wrap: wrap; gap: var(--space-2); }

  .facts { margin: 0; display: flex; flex-direction: column; gap: var(--space-1); }
  .facts dt { font-size: 0.8rem; color: var(--ink-soft); }
  .facts dd { margin: 0 0 var(--space-2); }
  .address { font-family: var(--font-mono); font-size: 0.9rem; color: var(--ink-text); overflow-wrap: anywhere; }
  /* Structure is indigo: the code, and the words. Display size, in rem so text zoom enlarges it. */
  .code {
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
    font-size: 1.6rem;
    letter-spacing: 0.12em;
    color: var(--indigo);
  }
  .words {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2) var(--space-4);
    font-size: 1.15rem;
    color: var(--indigo);
  }
  .words .n { font-size: 0.85rem; color: var(--ink-soft); }

  /* A clock, not an animation: digits in a fixed-width slot, in the soft ink. */
  .time { margin: 0; font-size: 0.9rem; color: var(--ink-soft); }
  .clock {
    display: inline-block;
    min-width: 4ch;
    font-family: var(--font-mono);
    font-variant-numeric: tabular-nums;
    color: var(--ink-soft);
  }
  .ended { color: var(--ink-text); }

  button.indigo { background: var(--indigo); color: var(--on-accent); border-color: var(--indigo); }
</style>
