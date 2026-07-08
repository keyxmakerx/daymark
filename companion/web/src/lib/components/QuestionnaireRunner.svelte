<script lang="ts">
  import type { InstrumentDefinition, InstrumentResult, Item } from '../instruments/types'
  import { visibleItemIds } from '../instruments/predicate'
  import { scoreInstrument } from '../instruments/scoring'
  import { bandFramingFor, PROVENANCE_LABEL, PROVENANCE_GLYPH, provenanceDisclaimer, provenanceSource } from '../instruments/index'

  let { def, onDone }: { def: InstrumentDefinition; onDone?: (r: InstrumentResult) => void } = $props()

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

<div class="q card">
  {#if !submitted}
    <div class="head">
      <h2>{def.title}</h2>
      <span class="prov prov-{def.provenance.tier}">{PROVENANCE_GLYPH[def.provenance.tier]} {PROVENANCE_LABEL[def.provenance.tier]}</span>
    </div>
    {#if provDisclaimer}
      <p class="prov-disclaimer" role="note">{provDisclaimer}</p>
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

    {#if error}<p class="error" role="alert">{error}</p>{/if}
    <button class="primary" onclick={submit}>Finish & see my results</button>
  {:else}
    <div class="head">
      <h2>{def.title} — your answers today</h2>
      <span class="prov prov-{def.provenance.tier}">{PROVENANCE_GLYPH[def.provenance.tier]} {PROVENANCE_LABEL[def.provenance.tier]}</span>
    </div>
    {#each result as sr (sr.scaleId)}
      <div class="result {toneClass(sr.scaleId)}">
        <p class="band">{sr.bandLabel}</p>
        <p class="score mono">score {sr.score}</p>
        <p class="framing faint">{bandFramingFor(def, sr.scaleId)}</p>
      </div>
    {/each}
    <p class="disclaimer faint">{def.framing.intro}</p>
  {/if}
</div>

<style>
  .q { display: flex; flex-direction: column; gap: var(--space-4); max-width: 40rem; }
  .head { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
  .head h2 { margin: 0; }
  .prov { font-size: 0.72rem; font-weight: 600; letter-spacing: 0.04em; text-transform: uppercase; padding: 0.15rem 0.55rem; border-radius: 999px; border: 1px solid var(--hairline); color: var(--ink-soft); white-space: nowrap; }
  .prov-validated { color: var(--mood-5); border-color: var(--mood-5); }
  .prov-custom { color: var(--mood-2); border-color: var(--mood-2); }
  .prov-disclaimer { margin: 0; font-size: 0.9rem; color: var(--ink-soft); border: 1px dashed var(--border-strong); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3); background: var(--paper-bg); }
  .prov-source { margin: 0; }
  .intro { margin: 0; }
  .info { background: var(--paper-bg); border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-3); color: var(--ink-soft); }
  fieldset { border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-3) var(--space-4); margin: 0; }
  legend { padding: 0 var(--space-2); font-weight: 560; }
  .options { display: flex; flex-direction: column; gap: var(--space-2); }
  .opt { display: flex; gap: var(--space-2); align-items: center; }
  textarea, input[type='number'] { font: inherit; width: 100%; padding: var(--space-2); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  .result { border: 1px solid var(--hairline); border-left-width: 4px; border-radius: var(--radius-sm); padding: var(--space-3) var(--space-4); }
  .tone-neutral { border-left-color: var(--mood-3); }
  .tone-attention { border-left-color: var(--mood-2); }
  .tone-positive { border-left-color: var(--mood-5); }
  .band { font-family: var(--font-display); font-size: 1.15rem; margin: 0 0 var(--space-1); }
  .score { margin: 0; color: var(--ink-soft); }
  .framing { margin: var(--space-2) 0 0; }
  .disclaimer { border-top: 1px solid var(--hairline); padding-top: var(--space-3); }
  .error { color: var(--mood-1); background: var(--mood-1-wash); border: 1px solid var(--mood-1); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3); margin: 0; }
  button { align-self: flex-start; }
</style>
