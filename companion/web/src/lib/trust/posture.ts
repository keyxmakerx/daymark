/*
 * WHICH POSTURE THE TRUST STRIP STATES, AS A RULE RATHER THAN AS AN EXPRESSION IN A COMPONENT.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * THE FAILURE THIS EXISTS TO MAKE IMPOSSIBLE
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * TrustBar.svelte says one of four things, and the `local` one is a promise: "This tab reads your
 * backup in the browser and sends nothing." The mapping from surface to posture used to be a
 * ternary inline in App.svelte — three tabs named explicitly, everything else falling through to
 * `local`. A fall-through is a fine default right up until the page grows a request that belongs
 * to none of the three named tabs, and then the default quietly becomes a false statement. That
 * is exactly what happened: a configuration probe was added on load, the setup screen said in so
 * many words that it was reading /v1/config, and the strip one element above it went on promising
 * that nothing left the browser.
 *
 * So the rule moved here, where it is a function a test can call with every input. The invariant
 * is one line and posture.test.ts asserts it over the whole surface union:
 *
 *     a page that is reaching the network is never described as sending nothing.
 *
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 * WHY THE TYPE LIVES HERE TOO
 * ─────────────────────────────────────────────────────────────────────────────────────────────
 *
 * It was declared twice — inline in TrustBar's instance script, and again in App.svelte's
 * `$derived` — with a comment explaining that a type exported from a Svelte 5 instance script is
 * not importable and one shared union was not worth a module for. It is worth a module now: the
 * union has a fourth member, and two copies of a union are two chances to add the member to one
 * of them.
 */
import type { SetupSurface } from '../setup/shape'

/**
 * What the strip is allowed to claim.
 *
 *   local     nothing leaves: open-a-file, self-checks, the tool builder, the practice panel.
 *   setup     the first-run screen, which reads the deployment's configuration and nothing else.
 *   sync      ciphertext leaves and returns; the passphrase does not.
 *   account   identifiers, and on recovery an email address, leave.
 */
export type TrustPosture = 'local' | 'setup' | 'sync' | 'account'

/**
 * The mapping. Two inputs, because two different things can put a request on the wire:
 *
 *   surface               which destination is on screen. Three of them talk to the server as
 *                         their whole purpose, and they say so regardless of anything else.
 *   readingConfiguration  whether THIS LOAD is reading GET /v1/config — which the page does only
 *                         while the setup question is open (lib/setup/shape.ts's
 *                         [shouldReadConfiguration]). It is a property of the page, not of the
 *                         tab, so it is passed in rather than inferred from the surface.
 *
 * ORDER MATTERS, AND NOT IN THE DIRECTION IT FIRST LOOKS. The surface's own posture is checked
 * first, and that is not the probe being hidden underneath it: `sync` and `account` both already
 * state that this tab reaches the server, so neither is weakened by a configuration read
 * happening alongside it, and both say more about what is at stake than `setup` does. Downgrading
 * either to `setup` would trade a precise sentence for a vaguer one. What must never happen is
 * the opposite — a load that reaches the network resolving to `local` — and that is the one case
 * the remaining line rules out.
 *
 * The practice panel falls to the last line with the file tab, and that is the correct reading
 * rather than an oversight: it makes no request at all — no client, no token, nothing fetched —
 * so it is as local as an unopened dropzone. The surface that does talk to a server about a
 * practice is practice.html, which states its own posture.
 */
export function trustPostureFor(
  surface: SetupSurface,
  readingConfiguration: boolean,
): TrustPosture {
  if (surface === 'sync') return 'sync'
  if (surface === 'owner' || surface === 'recover') return 'account'
  return readingConfiguration ? 'setup' : 'local'
}
