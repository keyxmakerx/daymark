<script lang="ts">
  /*
   * The honest "Trust strip".
   *
   * WHAT THIS USED TO SAY, AND WHY IT WAS WRONG. Until 2026-08-09 this component asserted
   * "This viewer reads your backup in the browser and makes no network requests. Your data
   * never leaves this device." — unconditionally, on every surface, because App.svelte
   * renders the strip outside the tab switch. That sentence was true on the "Open a backup
   * file" tab and false on three others: "Connect to sync" fetches from your server,
   * "Owner console" talks to it, and "Recover access" POSTs the email address you type into
   * the form directly beneath the claim. Worse, App.svelte lands you on "Recover access" by
   * default when you arrive from an emailed recovery link — so the user most likely to read
   * it was the one it was most wrong for.
   *
   * It also painted itself GREEN whenever navigator.onLine was false. Being offline at this
   * instant says nothing about whether the page would call out; COMPANION_UX.md 10.1 rules
   * that out explicitly ("the Phase-0 offline viewer is not painted --trust-locked (green)
   * ... a served page handling the master passphrase cannot provide a zero-knowledge
   * guarantee — so green would overclaim"). That reviewer must-fix was recorded as applied
   * and never reached the code.
   *
   * So: no green, and no self-asserted "makes no network requests" — a tampered page could
   * not be trusted to police itself, which is why the copy points at integrity verification
   * instead. The strip now states the posture of the surface you are actually on.
   *
   * TOKENS. The strip used to be washed in --mood-3-wash with a --mood-3 dot: the middle of
   * the DATA ramp, borrowed to mean "caution". The ramp encodes a person's reported experience
   * and nothing else (app.css, invariant 1), and the posture of a browser tab is not that
   * person's experience — so this was STATE wearing DATA's colours. It now takes the quiet
   * chrome ground, for the same reason NonDiagnosticBanner does:
   *
   *   - It is INFORMATION, not an alarm. It states what this surface is, and App.svelte renders
   *     it on EVERY surface, always. Amber is warn severity; spending it on something permanent
   *     wears it out until nothing means "warning" — and on the sync tab this strip stacks
   *     directly above SyncPanel's lower-assurance banner, which is a genuine warning and needs
   *     to stay visibly louder than its own frame.
   *   - Chrome is the machine talking about itself, which is exactly what this sentence is.
   *   - And it is still not green, which is the invariant that got this component rewritten.
   *
   * NOT ui/Callout: all three of its tones paint a coloured left rail, and a permanent frame
   * around the whole app should not read as a severity. Same call, same reasoning, as
   * owner/NonDiagnosticBanner.
   *
   * The decorative dot is gone rather than recoloured. It was aria-hidden and carried no
   * meaning the sentence did not already carry; a mark whose only job was to be mood-3 has no
   * job once it is not mood-3.
   */
  /*
   * THE FOURTH POSTURE, AND WHY THE UNION MOVED OUT OF THIS FILE.
   *
   * There were three, declared inline here and again in App.svelte, with a note that a type
   * exported from a Svelte 5 instance script is not importable and one shared union was not worth
   * a module for. Then a fourth arrived and the two copies became two places to forget it, so the
   * union and the mapping both live in lib/trust/posture.ts now — where trustPostureFor() is a
   * plain function, and posture.test.ts can assert over every surface that a page reaching the
   * network is never handed 'local'. That assertion is the whole reason this file no longer owns
   * the type: the guarantee below is only as good as what decides which branch prints.
   */
  import type { TrustPosture } from '../trust/posture'

  let { surface = 'local' }: { surface?: TrustPosture } = $props()
</script>

<aside class="trust" aria-label="Privacy and trust">
  <p>
    {#if surface === 'local'}
      <strong>Meant to run offline.</strong> This tab reads your backup in the browser and
      sends nothing — but a page cannot prove that about itself. Verify this build's integrity
      before you unlock an encrypted backup.
    {:else if surface === 'setup'}
      <!--
        THE FIRST-RUN SCREEN, WHICH IS THE ONE LOCAL-LOOKING SURFACE THAT DOES REACH THE SERVER.
        It asks the deployment whether it already names what this machine is for, and a page that
        asks a server anything may not, one element above the asking, promise that it sends
        nothing. The sentence is scoped in both directions on purpose — what the request carries,
        and that it stops once the question has an answer — because the reader's real question is
        whether the offline viewer they are about to use phones home, and the answer is that it
        does not.
      -->
      <strong>This screen reads your server’s configuration.</strong> It asks one thing —
      whether this deployment already names what this machine is for — and sends nothing about
      you or your journal. Once that question has an answer here, this page stops asking on
      load.
    {:else if surface === 'sync'}
      <strong>This tab talks to your server.</strong> Your passphrase and the decrypted
      entries stay in this browser; what crosses the network is ciphertext your server cannot
      read. It can still see that you synced, and when.
    {:else}
      <strong>This tab sends data to your server.</strong> Account actions here transmit
      identifiers, and account recovery transmits the email address you type. Your passphrase
      and your entries are not involved and never leave this browser.
    {/if}
  </p>
</aside>

<style>
  /* Chrome, never a wash from the data ramp and never green. Per COMPANION_UX.md 10.1 there
     is no green state for any of these surfaces: served portal JS is lower-assurance
     regardless of what the network is doing this second. See the header for why this is the
     quiet chrome ground rather than amber. */
  .trust {
    background: var(--chrome);
    border: 1px solid var(--chrome-hair);
    border-radius: var(--radius);
    padding: var(--space-3) var(--space-4);
    font-size: 0.9rem;
  }
  .trust p { margin: 0; color: var(--chrome-ink); }
</style>
