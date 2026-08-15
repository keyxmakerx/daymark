<script lang="ts">
  /*
   * The substrate the fixed honesty banners sit on.
   *
   * WHY IT EXISTS. Several screens carry copy the product is obliged to say and not allowed to
   * soften — "this is not a clinical assessment", "this log is not provably complete", "the
   * person has not opened this in eleven days". Before this component that copy was a loose
   * paragraph, and a loose paragraph reads as an aside: something the eye files next to the
   * content rather than as part of the claim. Callout gives it a body — a tinted ground and a
   * rule down its left edge — so the qualification is visibly attached to the thing it
   * qualifies and cannot be skimmed past as decoration.
   *
   * WHY IT DOES NOT TOUCH ITS CHILDREN. The banner wording is fixed, reviewed, and quoted
   * verbatim in the places that specify it. So `children` is rendered straight into a plain
   * container with no <p> wrapper, no truncation and no transformation: whatever markup the
   * call site passes is what a reader gets, and a future style tweak here can never quietly
   * reword a sentence someone signed off on. The only text this file authors is the
   * screen-reader severity prefix below.
   *
   * WHY THREE TONES AND NO MORE. Each maps to exactly one family in the token contract —
   * info to indigo (structural, the interface explaining itself), warn to amber (warn severity
   * only: provenance, degraded state), critical to clay (the single alarm hue: needs a human,
   * overdue, refused, destructive). A call site cannot pass a colour, so it cannot invent a
   * fourth meaning, and it cannot reach the mood ramp — which encodes a person's reported
   * experience and would be a lie on a banner about the software's limitations.
   *
   * WHY THERE IS NO 'ok' TONE. There is no green in this system (app.css, invariant 2). A
   * reassuring callout would be a claim — that we checked, that it held, that you may stop
   * reading — and this product is not in a position to make it. Reassurance here is the
   * absence of a callout, not a friendlier one.
   *
   * WHY THE TONE IS NEVER CARRIED BY HUE ALONE. Three separate non-colour signals:
   *   1. the words, which are the point and always say what is wrong;
   *   2. the edge FORM — info is a plain rule, warn is a broken rule, critical is the only
   *      tone that closes a full outline around itself. That ordering survives greyscale, a
   *      projector, a printout and any colour vision;
   *   3. a visually hidden severity prefix for warn and critical, so a screen reader gets the
   *      severity that sighted readers get from the edge.
   * Critical additionally swaps role="note" for role="alert", because a callout that means
   * "a human has to look at this" should interrupt rather than wait to be found.
   *
   * WHY THE TITLE IS SANS AND NOT THE DISPLAY SERIF. The serif is the content voice — a
   * person's material, a page subject (see Card, PageHeader). A callout is the machine talking
   * about its own limits, so it stays in the text face. The tone colour is spent on the title
   * only; the body stays --ink-soft so the caveat reads as prose rather than as a warning label.
   */
  import type { Snippet } from 'svelte'

  let {
    tone = 'info',
    title,
    children,
  }: {
    tone?: 'info' | 'warn' | 'critical'
    title?: string
    children: Snippet
  } = $props()

  const titleId = $props.id()

  /* Announced to assistive tech, never drawn. Mirrors what the edge form tells a sighted
     reader. Info has none on purpose: it is the neutral tone and needs no severity word. */
  const SEVERITY: Record<'info' | 'warn' | 'critical', string | null> = {
    info: null,
    warn: 'Warning',
    critical: 'Needs attention',
  }

  let severity = $derived(SEVERITY[tone])
</script>

<div
  class="callout"
  data-tone={tone}
  role={tone === 'critical' ? 'alert' : 'note'}
  aria-labelledby={title ? titleId : undefined}
>
  {#if severity}<span class="visually-hidden">{severity}:</span>{/if}
  {#if title}<p class="title" id={titleId}>{title}</p>{/if}
  <div class="body">{@render children()}</div>
</div>

<style>
  /* Every tone declares all four borders, so the box metrics are identical whether or not the
     outline is visible and a tone change cannot shift the copy sideways by a pixel. The three
     non-left edges are transparent by default; only critical paints them. */
  .callout {
    border: 1px solid transparent;
    border-left: 3px solid transparent;
    border-radius: var(--radius);
    padding: var(--space-3) var(--space-4);
    color: var(--ink-soft);
    font-size: 0.9rem;
    line-height: 1.55;
    /* The banners are prose, so they get a reading measure. Set here rather than on a wrapper
       around children, which would mean wrapping them. */
    max-width: 44rem;
  }

  /* Structural: the interface explaining how it is arranged, or what it does not know. */
  .callout[data-tone='info'] {
    background: var(--indigo-wash);
    border-left-color: var(--indigo);
  }
  .callout[data-tone='info'] .title {
    color: var(--indigo);
  }

  /* Warn severity only — provenance, degraded or unverified state. Not alarm, and never a
     stand-in for "fine". The broken rule is signal 2: warn reads as interrupted even with the
     hue stripped out. */
  .callout[data-tone='warn'] {
    background: var(--amber-wash);
    border-left-color: var(--amber);
    border-left-style: dashed;
  }
  .callout[data-tone='warn'] .title {
    color: var(--amber);
  }

  /* The single alarm hue: needs a human, overdue, refused, destructive. The only tone that
     closes a full outline around itself, so "this one is different" survives greyscale, a
     projector and a printout. */
  .callout[data-tone='critical'] {
    background: var(--clay-wash);
    border-color: var(--clay);
  }
  .callout[data-tone='critical'] .title {
    color: var(--clay);
  }

  .title {
    margin: 0 0 var(--space-1);
    font-family: var(--font-text);
    font-size: 0.9rem;
    font-weight: 600;
    line-height: 1.4;
    /* A long fixed banner heading wraps rather than pushing the page sideways. */
    overflow-wrap: anywhere;
  }

  /* Deliberately a bare container: the call site's markup passes through untouched. min-width
     lets a wide child (a table, a key) shrink instead of forcing a horizontal scrollbar on the
     page, and the child stays free to scroll itself. */
  .body {
    min-width: 0;
  }
</style>
