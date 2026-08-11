<script lang="ts">
  /*
   * The paper sheet — the signature content surface.
   *
   * WHY IT EXISTS. The design rule that generated this system is "cool chrome, warm content"
   * (see app.css). Chrome is the machine talking about itself: rails, bars, table heads,
   * timestamps. Card is the other half — the warm --paper-sheet ground a person's material
   * sits on. Every screen that shows someone's entries, answers or notes puts them on this,
   * and nothing else in the UI gets this surface, so "am I looking at my data or at the
   * product's furniture?" is answerable at a glance rather than by reading.
   *
   * WHY THE FOOTER IS A STRIP AND NOT A PARAGRAPH. The `footer` snippet is where the fixed,
   * non-diagnostic caveats live — "this is not a clinical assessment", "this log is not
   * provably complete". A caveat rendered as loose text under a card reads as a separate,
   * dismissible object, and the thing it qualifies goes on looking unqualified. So it is
   * full-bleed to the card's edges (negative margins cancel the sheet's padding), it shares
   * the card's bottom corner radius, and a hairline joins rather than separates it. It has to
   * look like part of the claim, because it is part of the claim.
   *
   * WHY NO MOOD TOKEN, DESPITE THE SPEC HANDED TO THIS FILE. The brief called for the strip
   * to be washed in the mid step of the mood ramp, inherited from the pre-rewrite components
   * (TrustBar, AuditCaveat) that borrowed the data ramp for caution. That is the exact
   * borrowing the token rewrite exists to end: the mood ramp encodes a person's reported
   * experience and nothing else, BandTag is the only primitive licensed to touch it, and a
   * card footer is interface, not data. The strip therefore uses the chrome ground — which is
   * also the honest reading of it, since a fixed product-supplied caveat *is* the machine
   * talking about itself. The warn wash was the other candidate and was rejected: warn is a
   * severity, and painting every card footer with it would spend the hue on boilerplate until
   * it meant nothing. (Note the ramp's own name is kept out of this file entirely, so the
   * planned "only BandTag references the ramp" grep stays a simple one.)
   *
   * NO GREEN ANYWHERE, INCLUDING BY ABSENCE OF NEED. There is no "ok" variant of this card.
   * A healthy card is just a card.
   */
  import type { Snippet } from 'svelte'

  let {
    title,
    tone = 'default',
    children,
    header,
    footer,
  }: {
    title?: string
    tone?: 'default' | 'quiet'
    children: Snippet
    header?: Snippet
    footer?: Snippet
  } = $props()
</script>

<section class="sheet" class:quiet={tone === 'quiet'}>
  {#if title || header}
    <header class="head">
      {#if title}<h2 class="title">{title}</h2>{/if}
      {#if header}<div class="head-end">{@render header()}</div>{/if}
    </header>
  {/if}

  <div class="body">{@render children()}</div>

  {#if footer}
    <div class="note">{@render footer()}</div>
  {/if}
</section>

<style>
  /* Not `.card`: app.css owns a global `.card` utility and a component class of the same
     name would inherit its padding on top of this one. */
  .sheet {
    background: var(--paper-sheet);
    border: 1px solid var(--hairline);
    border-radius: var(--radius);
    box-shadow: var(--elevation);
    padding: var(--space-5);
    color: var(--ink-text);
  }

  /* tone='quiet' — same sheet, no lift. For cards that are structure rather than subject:
     nested groupings, dense stacks where a dozen shadows turn into visual noise. */
  .sheet.quiet {
    box-shadow: none;
  }

  .head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: var(--space-2) var(--space-4);
    flex-wrap: wrap;
    margin: 0 0 var(--space-4);
  }

  .title {
    font-family: var(--font-display);
    font-size: 1.1rem;
    font-weight: 560;
    line-height: 1.25;
    margin: 0;
    color: var(--ink-text);
  }

  /* Chips, counts, provenance badges — pushed right, allowed to wrap under the title on
     narrow rather than squeezing it. */
  .head-end {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2);
    margin-left: auto;
  }

  .body {
    min-width: 0;
  }

  /* The note-strip. Negative margins cancel the sheet's padding so it reaches the border on
     three sides; the inset radius (border width subtracted) keeps it inside the sheet's
     rounded corners without an overflow:hidden that would clip a focus ring at the edge. */
  .note {
    margin: var(--space-5) calc(-1 * var(--space-5)) calc(-1 * var(--space-5));
    padding: var(--space-3) var(--space-5);
    background: var(--chrome);
    border-top: 1px solid var(--chrome-hair);
    border-radius: 0 0 calc(var(--radius) - 1px) calc(var(--radius) - 1px);
    color: var(--text-subtle);
    font-size: 0.8rem;
    line-height: 1.5;
  }
</style>
