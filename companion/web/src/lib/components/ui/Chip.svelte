<script lang="ts">
  /*
   * The chrome micro-label, as a component instead of a habit.
   *
   * WHAT IT IS FOR. Short machine facts that qualify the thing next to them — a provenance
   * marker ("Custom"), a scope ("Read only"), a count, an environment, a role. It is the
   * chrome layer talking about the content, which is why it is mono, uppercase and tracked
   * out: it must read as a field name, not as prose a person wrote.
   *
   * WHY TONE IS AN ENUM AND NOT A COLOUR PROP. Four tones, each mapped to exactly one family
   * in the token contract — neutral to chrome, accent to indigo, warn to amber, critical to
   * clay. A call site cannot pass a colour, so it cannot invent a fifth meaning, and it
   * cannot reach the mood ramp, which encodes a person's reported experience and would be a
   * lie on a chip about a tool's provenance.
   *
   * WHY THERE IS NO 'success' TONE. There is no green in this system. "Healthy" is the
   * absence of a chip, not the presence of a reassuring one — see app.css, invariant 2. Warn
   * (amber) means warn severity only and is never a stand-in for "fine"; critical (clay) is
   * the single alarm hue, kept scarce so that it still lands when it appears.
   *
   * The tone is never the only signal: every chip carries words, and the words are the point.
   */
  import type { Snippet } from 'svelte'

  let {
    tone = 'neutral',
    children,
  }: {
    tone?: 'neutral' | 'accent' | 'warn' | 'critical'
    children: Snippet
  } = $props()
</script>

<span class="chip" data-tone={tone}>{@render children()}</span>

<style>
  /* Same box metrics in every tone, so a row of chips sits on one baseline grid regardless of
     which ones happen to be alarming today. */
  .chip {
    display: inline-flex;
    align-items: center;
    padding: 0.125rem var(--space-2);
    border: 1px solid transparent;
    border-radius: var(--radius-sm);
    font-family: var(--font-mono);
    font-size: 10px;
    line-height: 1.5;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    white-space: nowrap;
    transition: background 180ms ease, border-color 180ms ease;
  }

  /* Chrome ground. The text is --chrome-ink rather than --chrome-soft because a chip sits ON
     the chrome fill, not next to it; --chrome-soft is the muted tone for labels sitting on
     paper, and stacking it on --chrome would drop this below a readable ratio at 10px. */
  .chip[data-tone='neutral'] {
    background: var(--chrome);
    border-color: var(--chrome-hair);
    color: var(--chrome-ink);
  }

  /* Structural emphasis — never data. Indigo marks how the interface is arranged. */
  .chip[data-tone='accent'] {
    background: var(--indigo-wash);
    border-color: color-mix(in srgb, var(--indigo) 40%, transparent);
    color: var(--indigo);
  }

  /* Warn severity only: provenance "Custom", console warn rows. Not alarm, not "attention". */
  .chip[data-tone='warn'] {
    background: var(--amber-wash);
    border-color: color-mix(in srgb, var(--amber) 45%, transparent);
    color: var(--amber);
  }

  /* The single alarm hue: needs a human, refused, destructive. */
  .chip[data-tone='critical'] {
    background: var(--clay-wash);
    border-color: color-mix(in srgb, var(--clay) 45%, transparent);
    color: var(--clay);
  }

  @media (prefers-reduced-motion: reduce) {
    .chip {
      transition: none;
    }
  }
</style>
