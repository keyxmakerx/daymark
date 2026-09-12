<script lang="ts">
  /*
   * PUTTING DOWN YOUR OWN ACCESS (issue #91).
   *
   * AT THE FOOT OF THE ALLOWED TAB, AND NOT BESIDE LOG OUT. Two adjacent buttons that both end a
   * session, one of them permanently, is a misclick with no recovery. The Allowed tab is where a
   * clinician reads what they hold in this relationship, which is the one screen where "I do not
   * want to hold this any more" is the thought somebody is already having.
   *
   * NO PASSPHRASE RE-ENTRY. It looks like care and is theatre: the passphrase protects the key
   * record, and the record is exactly the thing being given up. Asking for it would cost a person
   * something at the moment they have decided, and buy nothing — anyone at this screen is already
   * through the passphrase.
   *
   * CHROME, NOT CLAY, until the last button. Clay is this product's only alarm hue and means one of
   * four things: needs a human, overdue, refused, destructive. A clinician ending their own access
   * is an ordinary professional act and none of the first three; the destructive one is real, and it
   * is the confirm button, which is the single clay element here. Pressing the plain control only
   * opens a question. The same division the owner's revoke confirm makes, pointing the other way.
   *
   * THE ORDER OF THE CONFIRM IS THE DECISION. Irreversibility first, because it is the load-bearing
   * fact and the one a clinician may not have. The owner's side second: no clinician expects leaving
   * to delete a patient's journal, so leading with that reassurance answers a question they were not
   * asking at the cost of the one they need. Copies third. Then one flat sentence asking them to
   * tell the person, because nothing here will send a message.
   *
   * THE STANDING REVOKE CAVEAT IS NOT USED HERE, on purpose. "Revoking does not un-send what was
   * already read" is about a reader who already has something; from this side there is no such
   * reader and the clinician is not un-sending anything. The mirror constant says the true thing
   * instead. The exemption and its reason are written beside the check in
   * components/owner/revokeCaveat.test.ts.
   */
  import type { UnlockedContext } from '../../therapist/context'
  import { defaultKeyStorage } from '../../therapist/inviteAccept'
  import {
    leaveRelationship,
    KEEP_ACCESS,
    LEAVE_ACTION,
    LEAVE_CONFIRM,
    LEAVE_DOES_NOT_REACH_COPIES,
    LEAVE_DONE,
    LEAVE_IS_NOT_UNDONE,
    LEAVE_RECORD_REMAINS,
    LEAVE_SERVER_REFUSED,
    LEAVE_SESSION_MAY_REMAIN,
    LEAVE_TOUCHES_NOTHING_OF_THEIRS,
    LEAVE_TITLE,
    TELL_THEM_YOURSELF,
  } from '../../therapist/leave'

  let {
    ctx,
    onleft,
  }: {
    ctx: UnlockedContext
    /**
     * Handed the one true sentence for what happened, so the portal can drop the session and show
     * it on the screen the clinician lands on. Called only when the leave actually completed —
     * this component never tears down a session on a failure.
     */
    onleft: (notice: string) => void
  } = $props()

  let confirming = $state(false)
  let working = $state(false)
  /** What the last attempt did, when it did not complete. Never a congratulation; see the rules. */
  let refusal = $state('')

  async function leave() {
    working = true
    refusal = ''
    const outcome = await leaveRelationship(ctx.session.relRef, {
      serverLeave: () => ctx.client.leaveRelationship(ctx.session),
      storage: defaultKeyStorage(),
      logout: () => ctx.client.logout(ctx.session.csrf),
    })
    working = false
    if (outcome.ok) {
      confirming = false
      onleft(outcome.signedOut ? LEAVE_DONE : LEAVE_SESSION_MAY_REMAIN)
      return
    }
    /*
     * Three failures, three sentences, and they are genuinely different things to be told.
     *
     * `noRecord` reaching this screen would mean the portal is open on a browser that holds no key
     * record for this relationship, which should not be possible from here — but saying so plainly
     * is better than a blank, and it is the one case where the honest answer is that nothing was
     * ever here to leave.
     */
    refusal =
      outcome.reason === 'serverRefused'
        ? LEAVE_SERVER_REFUSED
        : outcome.reason === 'recordRemains'
          ? LEAVE_RECORD_REMAINS
          : 'This browser holds no keys for this person, so there is nothing here to leave.'
    // A record that would not go is not a state to offer a retry into: the irreversible half has
    // happened. Closing the question stops the button reading as "try that again".
    if (outcome.reason === 'recordRemains') confirming = false
  }
</script>

<section class="leave" aria-label="Your access">
  {#if !confirming}
    <button type="button" class="plain" onclick={() => (confirming = true)} disabled={working}>
      {LEAVE_ACTION}
    </button>
  {:else}
    <div class="confirm" role="group" aria-label={LEAVE_TITLE}>
      <p class="title">{LEAVE_TITLE}</p>
      <!-- The order is the decision; see the header. -->
      <p class="body">{LEAVE_IS_NOT_UNDONE}</p>
      <p class="body">{LEAVE_TOUCHES_NOTHING_OF_THEIRS}</p>
      <p class="body">{LEAVE_DOES_NOT_REACH_COPIES}</p>
      <p class="body">{TELL_THEM_YOURSELF}</p>
      <div class="actions">
        <button type="button" class="plain" onclick={() => (confirming = false)} disabled={working}>
          {KEEP_ACCESS}
        </button>
        <button type="button" class="destructive" onclick={leave} disabled={working}>
          {LEAVE_CONFIRM}
        </button>
      </div>
    </div>
  {/if}

  {#if refusal}
    <!-- A statement of what happened, on plain ink. Not a callout, not a tone, not an alarm: the
         clinician asked for something and this says what the machine did about it. -->
    <p class="refusal" role="status">{refusal}</p>
  {/if}
</section>

<style>
  /* A hairline above it, and nothing else. This sits at the foot of a read-only panel and should
     read as the end of the page rather than as a second region competing with it. */
  .leave {
    margin-top: var(--space-5);
    padding-top: var(--space-4);
    border-top: 1px solid var(--hairline);
  }
  .confirm {
    background: var(--chrome);
    border: 1px solid var(--chrome-hair);
    border-radius: var(--radius-sm);
    padding: var(--space-3);
  }
  .title { margin: 0 0 var(--space-2); color: var(--chrome-ink); font-weight: 600; font-size: 0.95rem; }
  .body { margin: 0 0 var(--space-2); max-width: 44rem; color: var(--chrome-ink); font-size: 0.9rem; line-height: 1.55; }
  .actions { display: flex; gap: var(--space-2); flex-wrap: wrap; margin-top: var(--space-3); }
  .refusal {
    margin: var(--space-3) 0 0;
    max-width: 44rem;
    color: var(--ink-text);
    font-size: 0.9rem;
    line-height: 1.55;
  }
  button { font: inherit; padding: var(--space-1) var(--space-3); border-radius: var(--radius-sm); cursor: pointer; }
  .plain { background: transparent; border: 1px solid var(--border-strong); color: var(--ink-text); }
  /* The one clay element on this surface. */
  .destructive { background: var(--clay); border: 1px solid var(--clay); color: var(--on-accent); }
  button:disabled { opacity: 0.6; cursor: default; }
</style>
