<script lang="ts">
  import type { InstrumentDefinition, InstrumentResult, Item } from '../instruments/types'
  import { visibleItemIds } from '../instruments/predicate'
  import { scoreInstrument } from '../instruments/scoring'
  import { bandFramingFor, provenanceDisclaimer, provenanceSource } from '../instruments/index'
  import { Callout, Card, ProvenanceBadge } from './ui'

  let { def, onDone }: { def: InstrumentDefinition; onDone?: (r: InstrumentResult) => void } = $props()

  /** No scales at all — a guided exercise rather than a questionnaire. */
  const unscored = $derived((def.scoring?.scales ?? []).length === 0)
  /** The last info item's body: what the exercise itself says on the way out. */
  const closingBody = $derived(
    [...def.items].reverse().find((i) => i.type === 'info' && !!i.body)?.body ?? '',
  )

  const provDisclaimer = $derived(provenanceDisclaimer(def.provenance))
  const provSource = $derived(provenanceSource(def.provenance))

  let answers = $state<Record<string, unknown>>({})
  let submitted = $state(false)
  let error = $state('')

  const visible = $derived(visibleItemIds(def.items, answers))
  const shownItems = $derived(def.items.filter((i) => visible.has(i.id)))
  const result = $derived(submitted ? scoreInstrument(def, answers) : [])

  function requiredMissing(): boolean {
    return shownItems.some((it) => it.required && (answers[it.id] == null || answers[it.id] === ''))
  }

  function submit() {
    if (requiredMissing()) {
      error = 'Please answer the required questions before finishing.'
      return
    }
    error = ''
    submitted = true
    const r: InstrumentResult = {
      kind: 'instrument',
      instrumentId: def.instrumentId,
      instrumentVersion: def.instrumentVersion,
      takenAt: Date.now(),
      scales: scoreInstrument(def, answers),
      answers: { ...answers },
    }
    onDone?.(r)
  }

  function toggleMulti(itemId: string, optId: string) {
    const cur = (answers[itemId] as string[] | undefined) ?? []
    answers[itemId] = cur.includes(optId) ? cur.filter((x) => x !== optId) : [...cur, optId]
  }

  function toneClass(scaleId: string): string {
    const s = result.find((x) => x.scaleId === scaleId)
    return s ? `tone-${s.tone}` : ''
  }

  function itemIsFreeText(it: Item) {
    return it.type === 'freeText'
  }
</script>

<!-- The provenance badge rides the card header in both phases: a reader must never have to
     remember or scroll back to find out what instrument they are holding. -->
{#snippet provBadge()}
  <ProvenanceBadge tier={def.provenance.tier} />
{/snippet}

<!-- The fixed non-diagnostic framing, as Card's note-strip rather than a loose trailing
     paragraph — it is part of the claim, not an aside filed next to it. The text is
     definition-supplied fixed copy and is rendered verbatim. -->
{#snippet framingNote()}
  <p class="disclaimer">{def.framing.intro}</p>
{/snippet}

<div class="q">
  <Card
    title={submitted ? `${def.title} — your answers today` : def.title}
    header={provBadge}
    footer={submitted ? framingNote : undefined}
  >
    <div class="stack">
  {#if !submitted}
    <!-- Fixed provenance copy. Warn severity for 'custom' (the one genuine caveat: not a
         validated or clinical instrument); info for 'adapted', matching what ProvenanceBadge
         already tells the reader about the tier. -->
    {#if provDisclaimer}
      <Callout tone={def.provenance.tier === 'custom' ? 'warn' : 'info'}>{provDisclaimer}</Callout>
    {:else if provSource}
      <p class="prov-source faint">Source: {provSource}</p>
    {/if}
    <p class="intro muted">{def.framing.intro}</p>

    {#each shownItems as it (it.id)}
      {#if it.type === 'info'}
        <div class="info">{it.body}</div>
      {:else}
        <fieldset>
          <legend>{it.prompt}{#if it.required}<span aria-hidden="true"> *</span>{/if}</legend>

          {#if it.type === 'likert' || it.type === 'singleSelect'}
            <div class="options">
              {#each it.options ?? [] as opt (opt.id)}
                <label class="opt">
                  <input type="radio" name={it.id} value={opt.id} checked={answers[it.id] === opt.id} onchange={() => (answers[it.id] = opt.id)} />
                  <span>{opt.label}</span>
                </label>
              {/each}
            </div>
          {:else if it.type === 'multiSelect'}
            <div class="options">
              {#each it.options ?? [] as opt (opt.id)}
                <label class="opt">
                  <input type="checkbox" checked={((answers[it.id] as string[]) ?? []).includes(opt.id)} onchange={() => toggleMulti(it.id, opt.id)} />
                  <span>{opt.label}</span>
                </label>
              {/each}
            </div>
          {:else if it.type === 'slider'}
            <input type="range" min={it.min ?? 0} max={it.max ?? 10} step={it.step ?? 1} value={(answers[it.id] as number) ?? it.min ?? 0} oninput={(e) => (answers[it.id] = Number((e.currentTarget as HTMLInputElement).value))} />
            <span class="mono">{(answers[it.id] as number) ?? '—'} {it.unit ?? ''}</span>
          {:else if it.type === 'numeric'}
            <input type="number" min={it.min} max={it.max} value={(answers[it.id] as number) ?? ''} oninput={(e) => (answers[it.id] = Number((e.currentTarget as HTMLInputElement).value))} />
          {:else if itemIsFreeText(it)}
            <textarea rows="3" value={(answers[it.id] as string) ?? ''} oninput={(e) => (answers[it.id] = (e.currentTarget as HTMLTextAreaElement).value)}></textarea>
          {/if}
        </fieldset>
      {/if}
    {/each}

    {#if error}<Callout tone="critical">{error}</Callout>{/if}
    <button class="primary" onclick={submit}>{unscored ? 'Finish' : 'Finish & see my results'}</button>
  {:else if unscored}
    <!--
      A guided exercise has no scales, so `result` is empty and the scored branch below would render
      an empty card under a "your results" heading. There is no score to show and that is the point:
      these end by saying so, using the definition's own closing words rather than copy invented here.
    -->
    <div class="done">
      <p class="closing">{closingBody || 'That is the end.'}</p>
      <p class="framing faint">Nothing here was scored, and nothing you wrote leaves this device.</p>
    </div>
  {:else}
    {#each result as sr (sr.scaleId)}
      <div class="result {toneClass(sr.scaleId)}">
        <p class="band">{sr.bandLabel}</p>
        <p class="score mono">score {sr.score}</p>
        <p class="framing faint">{bandFramingFor(def, sr.scaleId)}</p>
      </div>
    {/each}
  {/if}
    </div>
  </Card>
</div>

<style>
  .q { max-width: 40rem; }
  .done { display: flex; flex-direction: column; gap: var(--space-3); }
  .closing { font-family: var(--font-display); font-size: 1.05rem; line-height: 1.5; margin: 0; }
  .stack { display: flex; flex-direction: column; gap: var(--space-4); }
  .prov-source { margin: 0; }
  .intro { margin: 0; }
  .info { background: var(--paper-bg); border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-3); color: var(--ink-soft); }
  fieldset { border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-3) var(--space-4); margin: 0; }
  legend { padding: 0 var(--space-2); font-weight: 560; }
  .options { display: flex; flex-direction: column; gap: var(--space-2); }
  .opt { display: flex; gap: var(--space-2); align-items: center; }
  textarea, input[type='number'] { font: inherit; width: 100%; padding: var(--space-2); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  .result { border: 1px solid var(--hairline); border-left-width: 4px; border-radius: var(--radius-sm); padding: var(--space-3) var(--space-4); }

  /* DATA — DO NOT MIGRATE THESE TO CHROME/INDIGO/CLAY/AMBER.
     This edge is the band the person's own answers scored into, on the scale the instrument's
     author defined. It is the same ramp BandTag paints, used for exactly what the ramp means:
     a person's reported experience. It is never the only signal — .band prints the band's own
     descriptive label directly above it, and .framing prints the non-diagnostic caveat below.
     A future reader "fixing" this to indigo would be deleting the legend, not a violation. */
  .tone-neutral { border-left-color: var(--mood-3); }
  .tone-attention { border-left-color: var(--mood-2); }
  .tone-positive { border-left-color: var(--mood-5); }

  .band { font-family: var(--font-display); font-size: 1.15rem; margin: 0 0 var(--space-1); }
  .score { margin: 0; color: var(--ink-soft); }
  .framing { margin: var(--space-2) 0 0; }
  /* Sits in Card's note-strip, which supplies the ground, rule and type scale. */
  .disclaimer { margin: 0; }
  button { align-self: flex-start; }
</style>
