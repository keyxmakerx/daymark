<script lang="ts">
  /*
   * SIGN-IN — the two-column contract, and the deployment's digest as a control.
   *
   * WHAT THIS FILE IS ALLOWED TO DECIDE. Arrangement, and nothing else. Every sentence on this
   * screen is a premade constant in lib/therapist/signIn.ts, every rule about whether the
   * contract may be shown at all is checked there, and the digest is parsed, grouped and
   * shortened there. What is left here is two columns, a button, and the order things appear in.
   *
   * WHY THIS SCREEN OWNS NO AUTHENTICATION. LoginGate.svelte already holds the whole credential
   * path — TOTP verify, the local unwrap of the reading keys, the pinned owner keys. A second
   * component that also took a passphrase would be a second authentication path, which is a
   * security defect however carefully it were written: two places to review, two places to get
   * the key handling wrong, and a person who cannot tell which one they are typing into. So the
   * credential column arrives as a SNIPPET the caller supplies, and this file imports nothing
   * from session, keyStore or assignments/crypto. signIn.test.ts asserts that absence, having
   * first proved on LoginGate that its detectors can see an auth path when there is one.
   *
   * WHY THE CONTRACT COMES FIRST IN THE DOCUMENT. Signing in here opens another person's record.
   * The contract is therefore the first thing in reading order and the first thing on a narrow
   * window, above the fields rather than beside them — and it is rendered whole, with no
   * "show more" and no collapsed section, because a contract you have to expand is one the
   * reader is entitled to assume did not matter.
   *
   * WHY THE DIGEST SITS ABOVE THE FIELDS AND NOT UNDER THEM. "Promoted from footnote to
   * control" (COMPANION_WEB_REDESIGN_PLAN.md, Phase 3 item 4) is a claim about placement as much
   * as about markup: which image is serving this page is something to read BEFORE typing a
   * passphrase into it, so it is the first thing in the credential column, at the same weight as
   * everything else there, with its value legible in mono and copyable in one click.
   *
   * WHY NOTHING HERE SAYS WHETHER THE DIGEST IS RIGHT. There is no expected value on this
   * screen, no comparison and no verdict. The comparison would be performed by the same page
   * whose integrity is the question, so a tampered page would simply report that it matched —
   * and the result would be the software telling an operator that things checked out, which is
   * the shape this product does not draw. The value is displayed and copyable so it can be
   * checked somewhere that is not this page.
   *
   * WHY "COPIED" IS A WORD AND NOT A TICK. The copy control confirms in text, in the chrome
   * voice, and fades. There is no green check anywhere in this system (app.css, invariant 2),
   * and a copy button is not the place to introduce the first one.
   *
   * WHY THE ASSURANCE BANNER IS UNCONDITIONAL. LowerAssuranceBanner renders its own fixed copy;
   * this file does not retype a word of it, and takes no prop that could hide, reorder or
   * qualify it. It is rendered once, at the top, outside every {#if} on this screen.
   */
  import type { Snippet } from 'svelte'
  import { Card, PageHeader, Callout, Chip } from '../ui'
  import LowerAssuranceBanner from './LowerAssuranceBanner.svelte'
  import {
    SCREEN_COPY,
    checkContract,
    contractSections,
    describeDigest,
    digestReference,
    parseDigest,
  } from '../../therapist/signIn'

  let {
    imageDigest = null,
    credentials,
  }: {
    /**
     * The digest of the image serving this page, as the deployment reports it (`sha256:…`).
     * Null when the deployment reports none — which is a fact this screen states plainly rather
     * than hides. It is a claim by this page about itself and is never treated as more.
     */
    imageDigest?: string | null
    /**
     * The credential entry. Supplied by the caller — in this product that is LoginGate, which
     * owns the only authentication path there is. Required: a sign-in screen with no way to
     * sign in is not a state worth having a default for.
     */
    credentials: Snippet
  } = $props()

  /* Both are checks over a module constant, so they are computed once rather than tracked. */
  const contract = checkContract()
  const sections = contractSections()

  const parsed = $derived(parseDigest(imageDigest))
  const digest = $derived(describeDigest(imageDigest))

  /*
   * The compact marker beside the page title. `digestReference` will not return a shortened form
   * without the set it has to stay distinct from — here, the one digest this screen knows — and
   * falls back to the whole value if a short form could ever name two images. The whole value is
   * in the control below either way; this line is an identity marker, never the thing to compare.
   */
  const reference = $derived(parsed.ok ? digestReference(parsed.digest, [parsed.digest]) : null)

  type CopyState = 'idle' | 'copied' | 'unavailable'
  let copyState = $state<CopyState>('idle')
  let noticeTimer: ReturnType<typeof setTimeout> | null = null

  /** How long the copy confirmation stays before the control returns to rest. */
  const COPY_NOTICE_MS = 4000

  async function copyDigest(text: string) {
    if (noticeTimer !== null) clearTimeout(noticeTimer)
    try {
      /* No optional call: `navigator.clipboard?.writeText(x)` resolves to undefined when the API
         is missing, and the control would then claim a copy that never happened. */
      if (typeof navigator === 'undefined' || !navigator.clipboard) throw new Error('no clipboard')
      await navigator.clipboard.writeText(text)
      copyState = 'copied'
    } catch {
      copyState = 'unavailable'
    }
    noticeTimer = setTimeout(() => (copyState = 'idle'), COPY_NOTICE_MS)
  }

  $effect(() => () => {
    if (noticeTimer !== null) clearTimeout(noticeTimer)
  })
</script>

<section class="signin">
  <PageHeader title={SCREEN_COPY.pageTitle}>
    {#snippet trailing()}
      {#if reference !== null}
        <span class="marker">
          <span class="u-label">Image</span>
          <span class="u-mono ref">{reference}</span>
        </span>
      {/if}
    {/snippet}
  </PageHeader>

  <!-- Fixed copy, rendered by the component that owns it. Never inside a conditional. -->
  <LowerAssuranceBanner />

  <div class="grid">
    <!-- Column one: what this is. First in the document, and first on a narrow window. -->
    <div class="col">
      <Card title={SCREEN_COPY.contractTitle}>
        <p class="lede">{SCREEN_COPY.contractLede}</p>

        {#each sections as group (group.section)}
          <section class="clause-group">
            <h3 class="heading">{group.heading}</h3>
            <ul class="clauses">
              {#each group.clauses as clause (clause.id)}
                <li>{clause.text}</li>
              {/each}
            </ul>
          </section>
        {/each}
      </Card>
    </div>

    <!-- Column two: which page you are on, then the credential. In that order, deliberately. -->
    <div class="col">
      <Card title={SCREEN_COPY.digestTitle}>
        {#snippet header()}
          {#if digest.kind === 'digest'}<Chip>{digest.algorithmLabel}</Chip>{/if}
        {/snippet}

        {#if digest.kind === 'digest'}
          <!--
            The whole value, never elided. Groups carry no whitespace between them in the markup,
            so the gaps are drawn by CSS and a manual selection still yields `sha256:<hex>`
            exactly — the same string the copy control puts on the clipboard.
          -->
          <p class="value u-mono"><span class="algo">{digest.algorithm}:</span>{#each digest.groups as group, i (i)}<span class="group">{group}</span>{/each}</p>

          <div class="controls">
            <button type="button" onclick={() => copyDigest(digest.copyText)}>
              {SCREEN_COPY.digestCopy}
            </button>
            <span class="notice" role="status">
              {#if copyState === 'copied'}{SCREEN_COPY.digestCopied}{/if}
              {#if copyState === 'unavailable'}{SCREEN_COPY.digestCopyUnavailable}{/if}
            </span>
          </div>

          <p class="note">{SCREEN_COPY.digestNote}</p>
        {:else}
          <!-- An unreadable or missing digest is a real, standing limitation of this path, so it
               takes warn severity — not alarm, and not an accusation. -->
          <Callout tone="warn">
            <p class="fault">{digest.message}</p>
            {#if digest.reported !== null}
              <p class="reported u-mono">{digest.reported}</p>
            {/if}
          </Callout>
          <p class="note">{SCREEN_COPY.digestNote}</p>
        {/if}
      </Card>

      <!--
        SIGN-IN IS ALWAYS RENDERED. The contract check reports; it never withholds.

        This used to gate the credential entry on `contract.complete`, so a future edit that
        shortened one clause below the minimum length would have taken the portal offline and shown
        a clinician strings like `Clause "cannot.passphrase" is too short to state anything.` That
        is a copy-editing mistake escalated into an outage, on a product people reach for on their
        worst days, to defend against a fault the vitest suite already fails on before it can ship.

        The check is still worth having on screen — a hollowed-out contract means someone is
        consenting to less than they think — so the finding is surfaced ABOVE the entry, where the
        one reader who can act on it will see it, without standing between anyone and the door.
      -->
      {#if !contract.complete}
        <Card title={SCREEN_COPY.refusalCardTitle}>
          <Callout tone="warn" title={SCREEN_COPY.refusalTitle}>
            <p class="fault">{SCREEN_COPY.refusalBody}</p>
          </Callout>
          <ul class="problems u-mono">
            {#each contract.problems as problem, i (i)}
              <li>{problem}</li>
            {/each}
          </ul>
        </Card>
      {/if}

      <Card title={SCREEN_COPY.credentialTitle}>
        {@render credentials()}
      </Card>
    </div>
  </div>
</section>

<style>
  .signin {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    max-width: var(--maxw);
  }

  /* The build marker beside the title: chrome talking about itself, subordinate to everything
     else in the header. The whole digest lives in the control below. */
  .marker {
    display: inline-flex;
    align-items: baseline;
    gap: var(--space-2);
    min-width: 0;
  }

  .ref {
    font-size: 0.75rem;
    color: var(--text-subtle);
    overflow-wrap: anywhere;
  }

  /*
   * Two columns on a desk monitor, one on anything narrower. The contract is first in the
   * document, so the narrow layout stacks it above the fields rather than below them — the
   * reading order is the point of the screen, not a consequence of the grid.
   */
  .grid {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: var(--space-5);
  }

  .col {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    min-width: 0;
  }

  @media (min-width: 62rem) {
    .grid {
      grid-template-columns: minmax(0, 1.05fr) minmax(0, 1fr);
      align-items: start;
    }
  }

  .lede {
    margin: 0 0 var(--space-4);
    color: var(--ink-soft);
    font-size: 0.9rem;
    line-height: 1.6;
  }

  .clause-group + .clause-group {
    margin-top: var(--space-5);
  }

  /* The section headings inside the contract card. Text face rather than the display serif:
     these are the machine setting out its own terms, not a person's material. */
  .heading {
    margin: 0 0 var(--space-2);
    font-family: var(--font-text);
    font-size: 0.8rem;
    font-weight: 600;
    letter-spacing: 0.02em;
    color: var(--chrome-ink);
  }

  .clauses {
    margin: 0;
    padding-left: var(--space-4);
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
    color: var(--ink-text);
    font-size: 0.9rem;
    line-height: 1.6;
  }

  .clauses li {
    max-width: 44rem;
  }

  /*
   * The digest itself. Chrome ground, because this is the machine identifying itself, and the
   * full value at a size meant to be read character by character rather than skimmed.
   * `user-select: all` makes the whole value one click to select for the browsers where the
   * clipboard API is not available; `overflow-wrap: anywhere` lets it wrap inside the card
   * instead of pushing the page sideways.
   */
  .value {
    margin: 0;
    padding: var(--space-3);
    background: var(--chrome);
    border: 1px solid var(--chrome-hair);
    border-radius: var(--radius-sm);
    color: var(--chrome-ink);
    font-size: 0.85rem;
    line-height: 1.7;
    overflow-wrap: anywhere;
    user-select: all;
  }

  /* The algorithm prefix reads as part of the value, because it is: the copied string starts
     here. Slightly quieter so the eye lands on the hex, which is the part that differs. */
  .algo {
    color: var(--chrome-soft);
  }

  /* Reading chunks. The gap is drawn, never typed — see the note in the markup. */
  .group + .group {
    margin-left: 0.5ch;
  }

  .controls {
    display: flex;
    align-items: center;
    flex-wrap: wrap;
    gap: var(--space-2) var(--space-3);
    margin-top: var(--space-3);
  }

  /* The copy confirmation. A sentence in the chrome voice, and nothing else — no icon, no
     colour change, no tick. It empties itself after a few seconds. */
  .notice {
    color: var(--text-subtle);
    font-size: 0.8rem;
    min-height: 1.2em;
  }

  .note {
    margin: var(--space-3) 0 0;
    color: var(--text-subtle);
    font-size: 0.8rem;
    line-height: 1.55;
    max-width: 44rem;
  }

  .fault {
    margin: 0;
  }

  /* What the deployment reported when it could not be read as a digest. Machine text, quoted
     back so the operator can see the actual string rather than a description of it. */
  .reported {
    margin: var(--space-2) 0 0;
    font-size: 0.8rem;
    overflow-wrap: anywhere;
  }

  .problems {
    margin: var(--space-3) 0 0;
    padding-left: var(--space-4);
    color: var(--text-subtle);
    font-size: 0.8rem;
    line-height: 1.6;
  }
</style>
