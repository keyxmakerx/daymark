<script lang="ts">
  /*
   * What a screen says when there is nothing on it.
   *
   * WHY IT EXISTS. The product currently has none of these, and it is the single biggest
   * reason a clinician opening it for the first time cannot tell what the tool does. An empty
   * table with a header row and no rows communicates nothing except that something is missing;
   * the reader cannot distinguish "no one has shared anything with you yet" from "the request
   * failed" from "this feature is not for you". The emptiest screens are the ones a new person
   * sees most, so they are the ones that have to explain themselves.
   *
   * WHY THE COPY MUST NOT SCOLD. An empty week is a normal week. Someone who did not journal
   * for six days has not failed, and a tool that greets that with "No entries yet!" and a
   * button has decided, on no evidence, that more logging is better. So the register here is
   * flat and declarative — it states what this space holds and when something will appear in
   * it, and stops. Call sites should write copy that would read as fair if the person whose
   * data is missing were reading over the clinician's shoulder, because sooner or later they
   * are. No exclamation marks, no "yet" used as a nudge, no streak language.
   *
   * WHY IT IS QUIET. No illustration, no icon, no colour. An empty state is the lowest-value
   * thing on any screen; giving it a graphic would make absence the loudest object in the
   * layout, and giving it a hue would make it look like a status. It gets vertical space and
   * nothing else — the generous padding is what tells you the emptiness is deliberate rather
   * than a rendering failure. That also means there is no green "all clear" reading available
   * here, which is correct: this component never asserts that anything is fine, only that
   * nothing is here (app.css, invariant 2).
   *
   * WHY THE ACTION IS OPTIONAL AND SEPARATE. Some empty states have an honest next step
   * ("Invite someone to share with you"); most do not, and inventing one turns an explanation
   * into a prompt to do more. A missing `action` renders nothing at all rather than a disabled
   * or placeholder control.
   *
   * WHY THE TITLE IS NOT A HEADING ELEMENT. This lands inside cards, panels and table bodies
   * at depths this file cannot know, so any h-level it picked would be wrong somewhere and
   * would corrupt the document outline that heading-navigation users rely on. It is styled as
   * a title in the display serif — the content voice, because absent content is still the
   * subject here — and left out of the outline. The words below it carry the meaning either way.
   */
  import type { Snippet } from 'svelte'

  let {
    title,
    children,
    action,
  }: {
    title: string
    children?: Snippet
    action?: Snippet
  } = $props()
</script>

<div class="empty">
  <p class="title">{title}</p>
  {#if children}<div class="body">{@render children()}</div>{/if}
  {#if action}<div class="action">{@render action()}</div>{/if}
</div>

<style>
  /* The padding is the whole design. It is what separates "this is empty and we meant it"
     from "this did not load", so it stays generous even when the component sits in a dense
     table body. */
  .empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: var(--space-8) var(--space-5);
    text-align: center;
  }

  .title {
    margin: 0;
    font-family: var(--font-display);
    font-size: 1.15rem;
    font-weight: 560;
    line-height: 1.3;
    color: var(--ink-text);
    overflow-wrap: anywhere;
  }

  /* One paragraph of explanation, held to a reading measure so centred text does not run to
     the full width of a desk monitor. A bare container, not a <p>: the call site's markup
     passes through untouched and stays free to hold a link or an inline term. */
  .body {
    max-width: 34rem;
    margin-top: var(--space-3);
    color: var(--ink-soft);
    font-size: 0.9rem;
    line-height: 1.6;
    min-width: 0;
  }

  /* Wraps rather than overflowing: an empty state can carry two controls on a narrow panel. */
  .action {
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: var(--space-2);
    margin-top: var(--space-5);
  }

  @media (max-width: 34rem) {
    .empty {
      padding: var(--space-6) var(--space-4);
    }
  }
</style>
