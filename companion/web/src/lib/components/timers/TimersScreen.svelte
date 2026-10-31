<script lang="ts">
  /*
   * Session timers: for sessions, breaks and the time between, on this device only.
   *
   * Each timer tells the person in the way they chose — a chime, a buzz, a notification a watch
   * can show as a tap, or a quiet banner — and the banner always shows, so an alert the device
   * could not deliver is never lost (timers/alerts.ts). The clock is the wall clock, so a hidden
   * tab that ticked late still ends the timer on time (timers/timers.ts).
   *
   * Nothing here is sent anywhere or names a client: timers live in this component's state and go
   * when the page closes, and the screen says so (STAYS_HERE).
   */
  import { Card, PageHeader } from '../ui'
  import {
    ALERT_STYLES, PRESETS, addMinutes, clock, createTimer, isRunning, minutesProblem,
    parseMinutes, pause, remainingMs, resume, tick, type AlertStyle, type Timer,
  } from '../../timers/timers'
  import { askForNotifications, browserPorts, deliver } from '../../timers/alerts'
  import { ALERT_COPY, FELL_BACK, STAYS_HERE } from '../../timers/copy'

  /*
   * `open` hides the screen without unmounting it. The host keeps this component mounted for the
   * whole signed-in session, so a timer runs while the clinician is on another tab, and the banner
   * renders whether or not the screen is open: an alert that only showed on the Timers tab would
   * be lost on every other one.
   */
  let {
    open = true,
    presets = PRESETS,
  }: {
    open?: boolean
    /** The presets in the order this clinician reaches for them (therapist/discipline.ts). */
    presets?: readonly { label: string; minutes: number; nudgeMin: number }[]
  } = $props()

  let timers = $state<Timer[]>([])
  let now = $state(Date.now())
  let style = $state<AlertStyle>('screen')
  let label = $state('')
  let minutes = $state('15')
  let nudge = $state('5')
  let minutesError = $state('')
  let notifyRefused = $state(false)

  type Banner = { id: string; title: string; label: string; fired: string; fellBack: string | null; end: boolean }
  let banner = $state<Banner | null>(null)

  let seq = 0
  const nextId = () => `t${Date.now()}-${seq++}`

  const anyRunning = $derived(timers.some(isRunning))

  $effect(() => {
    if (!anyRunning) return
    const id = setInterval(step, 1000)
    return () => clearInterval(id)
  })

  // A tab brought back to the front catches up at once rather than on the next second.
  $effect(() => {
    const onVis = () => document.visibilityState === 'visible' && step()
    document.addEventListener('visibilitychange', onVis)
    return () => document.removeEventListener('visibilitychange', onVis)
  })

  function step() {
    now = Date.now()
    const r = tick(timers, now)
    timers = r.timers
    for (const ev of r.events) {
      const t = timers.find((x) => x.id === ev.id)
      if (t) announce(t, ev.kind)
    }
  }

  function announce(t: Timer, kind: 'nudge' | 'end' | 'test') {
    const title = kind === 'test' ? 'This is how a timer tells you' : kind === 'nudge' ? `${t.nudgeMin} minutes left` : 'Time'
    const { fellBack } = deliver(browserPorts, t.style, title, t.label)
    banner = {
      id: t.id,
      title,
      label: t.label,
      fired: ALERT_COPY[t.style].fired,
      fellBack: fellBack ? FELL_BACK[fellBack] : null,
      end: kind === 'end',
    }
  }

  async function choose(s: AlertStyle) {
    style = s
    notifyRefused = false
    if (s === 'watch') notifyRefused = !(await askForNotifications())
  }

  function start(l: string, mins: number, n: number) {
    now = Date.now()
    timers = [createTimer(nextId(), l, mins, n, style, now), ...timers]
  }

  function startOwn(e: SubmitEvent) {
    e.preventDefault()
    const problem = minutesProblem(minutes)
    minutesError = problem ?? ''
    if (problem) return
    start(label, parseMinutes(minutes), Number(nudge))
    label = ''
  }

  const update = (id: string, f: (t: Timer, now: number) => Timer) => {
    now = Date.now()
    timers = timers.map((t) => (t.id === id ? f(t, now) : t))
  }
  const toggle = (t: Timer) => update(t.id, isRunning(t) ? pause : resume)
  function more(id: string) {
    update(id, (t, n) => addMinutes(t, 5, n))
    if (banner?.id === id) banner = null
  }
  function stop(id: string) {
    timers = timers.filter((t) => t.id !== id)
    if (banner?.id === id) banner = null
  }

  const RING = 2 * Math.PI * 44
  const frac = (t: Timer) => (t.totalMs ? remainingMs(t, now) / t.totalMs : 0)
</script>

{#if banner}
  <div class="alertbar" role="status">
    <div class="atext">
      <b>{banner.title}</b>
      <span>{banner.label} · {banner.fellBack ?? banner.fired}</span>
    </div>
    <div class="actions">
      {#if banner.end && timers.some((t) => t.id === banner?.id)}
        <button type="button" onclick={() => banner && more(banner.id)}>5 more minutes</button>
      {/if}
      <button type="button" onclick={() => (banner = null)}>Dismiss</button>
    </div>
  </div>
{/if}

<div hidden={!open}>
<PageHeader title="Timers" />
<p class="lede">For sessions, breaks and the time between. Each one can chime, buzz, tap your watch, or just show here.</p>

{#if timers.length}
  <section class="stack" aria-labelledby="running-h">
    <h2 class="sec" id="running-h">Running</h2>
    <div class="tgrid">
      {#each timers as t (t.id)}
        <div class="tcard" class:done={t.done}>
          <svg class="ring" viewBox="0 0 100 100" aria-hidden="true">
            <circle cx="50" cy="50" r="44" class="rt" />
            <circle cx="50" cy="50" r="44" class="rv" style:stroke-dasharray={RING.toFixed(1)} style:stroke-dashoffset={(RING * (1 - frac(t))).toFixed(1)} />
          </svg>
          <div class="tbody">
            <b>{t.label}</b>
            <span class="left" class:over={t.done} role="timer" aria-label="Time left">{t.done ? 'Time' : clock(remainingMs(t, now))}</span>
            <span class="muted small">
              {ALERT_COPY[t.style].label}{t.nudgeMin ? ` · nudge at ${t.nudgeMin} min left` : ''}{!t.done && !isRunning(t) ? ' · paused' : ''}
            </span>
            <div class="actions">
              {#if t.done}
                <button type="button" onclick={() => more(t.id)}>5 more minutes</button>
                <button type="button" onclick={() => stop(t.id)}>Clear</button>
              {:else}
                <button type="button" onclick={() => toggle(t)}>{isRunning(t) ? 'Pause' : 'Carry on'}</button>
                <button type="button" onclick={() => more(t.id)}>+5 min</button>
                <button type="button" class="quiet" onclick={() => stop(t.id)}>Stop</button>
              {/if}
            </div>
          </div>
        </div>
      {/each}
    </div>
  </section>
{/if}

<section class="stack" aria-labelledby="presets-h">
  <h2 class="sec" id="presets-h">Start one</h2>
  <div class="tiles">
    {#each presets as p (p.label)}
      <button type="button" class="tile" onclick={() => start(p.label, p.minutes, p.nudgeMin)}>
        <span class="kick">{p.minutes} min</span>
        <b>{p.label}</b>
        <span class="muted small">{p.nudgeMin ? `A nudge at ${p.nudgeMin} min left` : 'No nudge, just the end'}</span>
      </button>
    {/each}
  </div>
</section>

<Card title="Your own">
  <form class="stack" onsubmit={startOwn}>
    <div class="cols">
      <div class="field">
        <label for="t-label">Name</label>
        <input id="t-label" bind:value={label} placeholder="For example, Room 2 ready" maxlength="60" />
      </div>
      <div class="field">
        <label for="t-min">Minutes</label>
        <input id="t-min" bind:value={minutes} inputmode="decimal" aria-invalid={minutesError ? 'true' : undefined} aria-describedby={minutesError ? 't-min-err' : undefined} />
        {#if minutesError}<span class="err" id="t-min-err">{minutesError}</span>{/if}
      </div>
    </div>
    <div class="field">
      <label for="t-nudge">A nudge before the end</label>
      <select id="t-nudge" bind:value={nudge}>
        <option value="0">No nudge</option>
        <option value="2">2 minutes before</option>
        <option value="5">5 minutes before</option>
        <option value="10">10 minutes before</option>
        <option value="15">15 minutes before</option>
      </select>
    </div>
    <fieldset class="field">
      <legend>How it tells you</legend>
      <div class="opts">
        {#each ALERT_STYLES as s}
          <label class="opt" class:on={style === s}>
            <input type="radio" name="t-style" value={s} checked={style === s} onchange={() => choose(s)} />
            <span><b>{ALERT_COPY[s].label}</b><small>{ALERT_COPY[s].detail}</small></span>
          </label>
        {/each}
      </div>
      <span class="muted small">The presets use this too.</span>
      {#if notifyRefused}
        <span class="muted small">{FELL_BACK.watch}</span>
      {/if}
    </fieldset>
    <div class="actions">
      <button type="submit" class="primary">Start</button>
      <button type="button" class="quiet" onclick={() => announce(createTimer('test', 'A test', 1, 0, style, Date.now()), 'test')}>Try how it tells you</button>
    </div>
  </form>
</Card>

<p class="muted small">{STAYS_HERE}</p>
</div>

<style>
  .lede { color: var(--ink-soft); margin: 0 0 1rem; max-width: 40rem; }
  .stack { display: flex; flex-direction: column; gap: 0.75rem; margin-bottom: 1.5rem; min-width: 0; }
  .sec { font-family: var(--font-text); font-size: 0.78rem; font-weight: 600; letter-spacing: 0.08em; text-transform: uppercase; color: var(--ink-soft); margin: 0; }
  .small { font-size: 0.86rem; }
  .actions { display: flex; flex-wrap: wrap; gap: 0.5rem; align-items: center; }
  .quiet { border-color: transparent; background: none; text-decoration: underline; text-underline-offset: 3px; color: var(--ink-soft); }

  .alertbar { position: sticky; top: 0.5rem; z-index: 5; display: flex; flex-wrap: wrap; gap: 0.75rem 1rem; align-items: center; justify-content: space-between; background: var(--indigo-wash); border-left: 3px solid var(--indigo); border-radius: var(--radius-sm); padding: 0.75rem 1rem; margin-bottom: 1rem; }
  .atext { display: flex; flex-direction: column; min-width: 0; }
  .atext b { color: var(--indigo-deep); }

  .tgrid { display: grid; grid-template-columns: repeat(auto-fill, minmax(17rem, 1fr)); gap: 0.75rem; }
  .tcard { display: grid; grid-template-columns: 4.5rem minmax(0, 1fr); gap: 1rem; align-items: center; background: var(--paper-sheet); border: 1px solid var(--hairline); border-radius: var(--radius); padding: 1rem; }
  .tcard.done { border-color: var(--indigo); }
  .tbody { display: flex; flex-direction: column; gap: 0.2rem; min-width: 0; }
  .left { font-family: var(--font-mono); font-size: 1.5rem; font-variant-numeric: tabular-nums; }
  .left.over { font-family: var(--font-display); }
  .ring { width: 4.5rem; height: 4.5rem; transform: rotate(-90deg); }
  .ring circle { fill: none; stroke-width: 7; }
  .rt { stroke: var(--hairline); }
  .rv { stroke: var(--indigo); stroke-linecap: round; transition: stroke-dashoffset 900ms linear; }
  @media (prefers-reduced-motion: reduce) { .rv { transition: none; } }

  .tiles { display: grid; grid-template-columns: repeat(auto-fill, minmax(9rem, 1fr)); gap: 0.75rem; }
  .tile { text-align: left; background: var(--paper-sheet); border: 1px solid var(--hairline); border-radius: var(--radius); padding: 1rem; display: flex; flex-direction: column; gap: 0.2rem; min-height: 0; }
  .tile b { font-family: var(--font-display); font-weight: 500; font-size: 1.08rem; }
  .kick { font-size: 0.7rem; letter-spacing: 0.08em; text-transform: uppercase; color: var(--ink-soft); font-weight: 650; }

  .cols { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1rem; }
  @media (max-width: 34rem) { .cols { grid-template-columns: minmax(0, 1fr); } }
  .field { display: flex; flex-direction: column; gap: 0.4rem; min-width: 0; border: 0; padding: 0; margin: 0; }
  .field label, .field legend { font-weight: 600; font-size: 0.95rem; padding: 0; }
  .field input:not([type='radio']), .field select { font: inherit; min-height: 2.75rem; padding: 0 0.75rem; border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); width: 100%; max-width: 28rem; }
  .err { font-size: 0.86rem; color: var(--clay); }
  .opts { display: grid; grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr)); gap: 0.5rem; }
  .opt { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 0.75rem; padding: 0.75rem 1rem; border: 1px solid var(--border-strong); border-radius: var(--radius); background: var(--paper-sheet); cursor: pointer; font-weight: 400; }
  .opt.on { border-color: var(--indigo); background: var(--indigo-wash); }
  .opt input { margin-top: 0.2rem; accent-color: var(--indigo); }
  .opt span { display: flex; flex-direction: column; }
  .opt small { color: var(--ink-soft); font-size: 0.86rem; }
</style>
