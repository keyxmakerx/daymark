import { mount } from 'svelte'
import './app.css'
import TherapistPortal from './lib/components/therapist/TherapistPortal.svelte'
import InviteAcceptance from './lib/components/therapist/InviteAcceptance.svelte'
import { isInvitePath, looksLikeInvite } from './lib/therapist/inviteLink'

const target = document.getElementById('therapist-app')
if (!target) throw new Error('#therapist-app mount point missing')

/*
 * WHICH OF THE TWO THERAPIST SURFACES THIS DOCUMENT IS.
 *
 * The server emails invitations pointing at `{base}/portal/invite#id=…&s=…` and serves this same
 * SPA entry there (Application.kt maps the clean paths to therapist.html; before that route existed
 * the emailed link was simply a 404, which is why the acceptance page had never been reachable).
 * One document, two surfaces, so the entry decides which to mount rather than the server serving
 * two bundles.
 *
 * WHY THE FRAGMENT IS ENOUGH ON ITS OWN, AND WHY THE PATH IS CHECKED TOO. The fragment is the
 * reliable signal: it is what the invitation actually carries, and it survives a deployment serving
 * the entry at any path it likes. The path is the second question for the case where the fragment
 * did not survive the trip — a chat client that cut the link at the `#`, a printed link retyped
 * without its tail. Somebody in that position has plainly come here to accept an invitation, and
 * the acceptance page can tell them what happened to their link. The sign-in gate cannot; it would
 * ask them for nine values they have never heard of.
 *
 * `looksLikeInvite` deliberately answers the weak question ("does this claim to be an invitation")
 * rather than the strong one ("is this a usable invitation"). Routing on the strong question would
 * send every mangled link to the wrong screen — which is exactly the population that needs the right
 * one. The strong question is asked once the page is up.
 */
const acceptingInvite = looksLikeInvite(window.location.hash) || isInvitePath(window.location.pathname)

// Branched at the call rather than at the argument: the two components take different props, so a
// ternary inside mount() asks TypeScript to reconcile two unrelated prop types and it correctly
// refuses. Each surface mounts itself.
const app = acceptingInvite ? mount(InviteAcceptance, { target }) : mount(TherapistPortal, { target })

export default app
