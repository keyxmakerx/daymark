<script lang="ts">
  import type { InstrumentDefinition, ItemType, ProvenanceTier, Tone } from '../instruments/types'
  import { newDraft, compileDraft, validateDraft } from '../instruments/builder'
  import type { DraftItem, ToolDraft } from '../instruments/builder'
  import { PROVENANCE_LABEL, provenanceDisclaimer } from '../instruments/index'
  import QuestionnaireRunner from './QuestionnaireRunner.svelte'

  let { onPublish }: { onPublish?: (def: InstrumentDefinition) => void } = $props()

  let draft = $state<ToolDraft>(newDraft())
  let seq = $state(1)

  const validation = $derived(validateDraft(draft))
  const compiled = $derived(compileDraft(draft))
  const disclaimer = $derived(provenanceDisclaimer(draft.provenance))

  const ITEM_TYPES: { v: ItemType; label: string }[] = [
    { v: 'likert', label: 'Scale (options)' },
    { v: 'singleSelect', label: 'Single choice' },
    { v: 'slider', label: 'Slider' },
    { v: 'numeric', label: 'Number' },
    { v: 'freeText', label: 'Free text' },
    { v: 'info', label: 'Info panel' },
  ]
  const TONES: Tone[] = ['neutral', 'attention', 'positive']

  function slug(s: string): string {
    return s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40)
  }

  function setTitle(v: string) {
    draft.title = v
    if (!draft.instrumentId || draft.instrumentId === slug(draft.title.slice(0, -1))) draft.instrumentId = slug(v)
  }

  function defaultItem(type: ItemType): DraftItem {
    const id = `q${seq}`
    seq += 1
    if (type === 'likert')
      return { id, type, prompt: '', required: true, options: [
        { id: 'o0', label: 'Not at all', value: 0 },
        { id: 'o1', label: 'A little', value: 1 },
        { id: 'o2', label: 'Somewhat', value: 2 },
        { id: 'o3', label: 'A lot', value: 3 },
      ] }
    if (type === 'singleSelect')
      return { id, type, prompt: '', required: true, options: [
        { id: 'o0', label: 'Option A', value: 0 },
        { id: 'o1', label: 'Option B', value: 1 },
      ] }
    if (type === 'slider') return { id, type, prompt: '', required: true, min: 0, max: 10, step: 1 }
    if (type === 'numeric') return { id, type, prompt: '', required: true }
    if (type === 'freeText') return { id, type, prompt: '', excludeFromScoring: true }
    return { id, type, body: '' }
  }

  function addItem(type: ItemType) {
    draft.items = [...draft.items, defaultItem(type)]
  }
  function removeItem(id: string) {
    draft.items = draft.items.filter((i) => i.id !== id)
  }
  function addOption(it: DraftItem) {
    const n = it.options?.length ?? 0
    it.options = [...(it.options ?? []), { id: `o${n}`, label: '', value: n }]
  }
  function removeOption(it: DraftItem, oid: string) {
    it.options = (it.options ?? []).filter((o) => o.id !== oid)
  }
  function setTier(t: ProvenanceTier) {
    draft.provenance = { ...draft.provenance, tier: t }
  }
  function addBand() {
    draft.bands = [...draft.bands, { label: '', tone: 'neutral' }]
  }
  function removeBand(i: number) {
    draft.bands = draft.bands.filter((_, idx) => idx !== i)
  }
  function publish() {
    if (validation.ok) onPublish?.(compiled)
  }

  const hasOptions = (t: ItemType) => t === 'likert' || t === 'singleSelect'
  const hasRange = (t: ItemType) => t === 'slider' || t === 'numeric'
</script>

<div class="builder">
  <div class="editor">
    <h2>New tool</h2>

    <section>
      <span class="lab">Details</span>
      <label class="fld">Title
        <input value={draft.title} oninput={(e) => setTitle((e.currentTarget as HTMLInputElement).value)} placeholder="Evening wind-down" />
      </label>
      <p class="hint mono">id: {draft.instrumentId || '—'}</p>
      <label class="fld">Intro (shown first)
        <textarea rows="2" value={draft.intro} oninput={(e) => (draft.intro = (e.currentTarget as HTMLTextAreaElement).value)}></textarea>
      </label>
    </section>

    <section>
      <span class="lab">Provenance <span class="req">*</span></span>
      <div class="seg">
        {#each ['validated', 'adapted', 'custom'] as const as t (t)}
          <button type="button" class:on={draft.provenance.tier === t} onclick={() => setTier(t)}>{PROVENANCE_LABEL[t]}</button>
        {/each}
      </div>
      {#if draft.provenance.tier === 'validated'}
        <label class="fld">Source (published instrument + citation)
          <input value={draft.provenance.source ?? ''} oninput={(e) => (draft.provenance = { ...draft.provenance, source: (e.currentTarget as HTMLInputElement).value })} placeholder="PHQ-9 (Kroenke et al., 2001)" />
        </label>
        <label class="fld">Ledger anchor
          <input value={draft.ledgerRef ?? ''} oninput={(e) => (draft.ledgerRef = (e.currentTarget as HTMLInputElement).value)} placeholder="INSTRUMENTS.md#phq-9" />
        </label>
      {:else if draft.provenance.tier === 'adapted'}
        <label class="fld">Based on (the method it draws from)
          <input value={draft.provenance.basedOn ?? ''} oninput={(e) => (draft.provenance = { ...draft.provenance, basedOn: (e.currentTarget as HTMLInputElement).value })} placeholder="behavioral activation" />
        </label>
      {/if}
      {#if disclaimer}<p class="disc">{disclaimer}</p>{/if}
    </section>

    <section>
      <span class="lab">Items</span>
      {#each draft.items as it (it.id)}
        <div class="item">
          <div class="item-head">
            <span class="mono type">{it.type}</span>
            <button type="button" class="link danger" onclick={() => removeItem(it.id)}>Remove</button>
          </div>
          {#if it.type === 'info'}
            <textarea rows="2" value={it.body ?? ''} oninput={(e) => (it.body = (e.currentTarget as HTMLTextAreaElement).value)} placeholder="Panel text…"></textarea>
          {:else}
            <input value={it.prompt ?? ''} oninput={(e) => (it.prompt = (e.currentTarget as HTMLInputElement).value)} placeholder="Question prompt…" />
            {#if hasOptions(it.type)}
              <div class="opts">
                {#each it.options ?? [] as o (o.id)}
                  <div class="opt-row">
                    <input class="grow" value={o.label} oninput={(e) => (o.label = (e.currentTarget as HTMLInputElement).value)} placeholder="Option label" />
                    <input class="num" type="number" value={o.value ?? 0} oninput={(e) => (o.value = Number((e.currentTarget as HTMLInputElement).value))} />
                    <button type="button" class="link danger" onclick={() => removeOption(it, o.id)} aria-label="Remove option">×</button>
                  </div>
                {/each}
                <button type="button" class="link" onclick={() => addOption(it)}>+ option</button>
              </div>
            {:else if hasRange(it.type)}
              <div class="range">
                <label>min <input type="number" value={it.min ?? 0} oninput={(e) => (it.min = Number((e.currentTarget as HTMLInputElement).value))} /></label>
                <label>max <input type="number" value={it.max ?? 10} oninput={(e) => (it.max = Number((e.currentTarget as HTMLInputElement).value))} /></label>
              </div>
            {/if}
          {/if}
        </div>
      {/each}
      <div class="add">
        {#each ITEM_TYPES as t (t.v)}
          <button type="button" class="link" onclick={() => addItem(t.v)}>+ {t.label}</button>
        {/each}
      </div>
    </section>

    <section>
      <span class="lab">Scoring &amp; framing</span>
      <div class="bands">
        {#each draft.bands as b, i (i)}
          <div class="band-row">
            <input class="num" type="number" value={b.min ?? ''} oninput={(e) => (b.min = (e.currentTarget as HTMLInputElement).value === '' ? undefined : Number((e.currentTarget as HTMLInputElement).value))} placeholder="min" />
            <input class="num" type="number" value={b.max ?? ''} oninput={(e) => (b.max = (e.currentTarget as HTMLInputElement).value === '' ? undefined : Number((e.currentTarget as HTMLInputElement).value))} placeholder="max" />
            <input class="grow" value={b.label} oninput={(e) => (b.label = (e.currentTarget as HTMLInputElement).value)} placeholder="Descriptive band label" />
            <select value={b.tone} onchange={(e) => (b.tone = (e.currentTarget as HTMLSelectElement).value as Tone)}>
              {#each TONES as tn (tn)}<option value={tn}>{tn}</option>{/each}
            </select>
            <button type="button" class="link danger" onclick={() => removeBand(i)} aria-label="Remove band">×</button>
          </div>
        {/each}
        <button type="button" class="link" onclick={addBand}>+ band</button>
      </div>
      <label class="fld">Band framing (must say “not a diagnosis/screen/clinical”)
        <textarea rows="2" value={draft.bandFraming} oninput={(e) => (draft.bandFraming = (e.currentTarget as HTMLTextAreaElement).value)}></textarea>
      </label>
    </section>

    <section class="gate" class:pass={validation.ok}>
      <span class="lab">Honesty gate {validation.ok ? '✓ passes' : `— ${validation.errors.length} to fix`}</span>
      {#if !validation.ok}
        <ul>
          {#each validation.errors as err (err)}<li>{err}</li>{/each}
        </ul>
      {/if}
      <button class="primary" disabled={!validation.ok} onclick={publish}>Publish…</button>
    </section>
  </div>

  <aside class="preview">
    <span class="lab">Live preview</span>
    {#key `${draft.items.length}:${draft.provenance.tier}`}
      <QuestionnaireRunner def={compiled} />
    {/key}
  </aside>
</div>

<style>
  .builder { display: grid; grid-template-columns: 1fr 22rem; gap: var(--space-5); align-items: start; }
  .editor { display: flex; flex-direction: column; gap: var(--space-4); min-width: 0; }
  section { display: flex; flex-direction: column; gap: var(--space-2); border-top: 1px solid var(--hairline); padding-top: var(--space-3); }
  .lab { font-size: 0.72rem; letter-spacing: 0.1em; text-transform: uppercase; color: var(--ink-soft); font-weight: 600; }
  .req { color: var(--mood-1); }
  .fld { display: flex; flex-direction: column; gap: var(--space-1); font-size: 0.85rem; color: var(--ink-soft); }
  input, textarea, select { font: inherit; padding: var(--space-2); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-text); }
  .hint { margin: 0; color: var(--ink-soft); font-size: 0.75rem; }
  .seg { display: flex; gap: var(--space-1); }
  .seg button { flex: 1; padding: var(--space-2); border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--paper-bg); color: var(--ink-soft); }
  .seg button.on { background: var(--accent, var(--mood-5)); color: #fff; border-color: transparent; font-weight: 600; }
  .disc { margin: 0; font-size: 0.82rem; color: var(--ink-soft); border: 1px dashed var(--border-strong); border-radius: var(--radius-sm); padding: var(--space-2); background: var(--paper-bg); }
  .item { border: 1px solid var(--hairline); border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3); display: flex; flex-direction: column; gap: var(--space-2); }
  .item-head { display: flex; justify-content: space-between; align-items: center; }
  .type { font-size: 0.7rem; text-transform: uppercase; color: var(--ink-soft); }
  .opts, .range, .bands { display: flex; flex-direction: column; gap: var(--space-1); }
  .opt-row, .band-row { display: flex; gap: var(--space-2); align-items: center; }
  .grow { flex: 1; } .num { width: 4.5rem; }
  .range { flex-direction: row; gap: var(--space-3); }
  .add { display: flex; flex-wrap: wrap; gap: var(--space-2); }
  .link { background: none; border: none; color: var(--accent, var(--mood-5)); font: inherit; font-size: 0.82rem; cursor: pointer; padding: 0; }
  .link.danger { color: var(--mood-1); }
  .gate ul { margin: 0; padding-left: 1.1rem; color: var(--mood-1); font-size: 0.82rem; display: flex; flex-direction: column; gap: 2px; }
  .gate.pass .lab { color: var(--mood-5); }
  .primary:disabled { opacity: 0.5; }
  .preview { position: sticky; top: var(--space-4); display: flex; flex-direction: column; gap: var(--space-2); border-left: 1px solid var(--hairline); padding-left: var(--space-4); }
  @media (max-width: 52rem) { .builder { grid-template-columns: 1fr; } .preview { border-left: none; border-top: 1px solid var(--hairline); padding-left: 0; padding-top: var(--space-3); position: static; } }
</style>
