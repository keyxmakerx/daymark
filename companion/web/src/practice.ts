/*
 * The practice console's entry point.
 *
 * Mounted with no props. `baseUrl` defaults to '' inside the console, because every route it calls
 * lives under the server's own `/v1/orgs` prefix and this page is served by that server — a
 * relative fetch reaches it from wherever the deployment happens to mount the app.
 *
 * NO CREDENTIAL IS BUILT IN AND NONE IS REMEMBERED. The console signs in through the same
 * PortalClient path the clinician portal uses, and holds the anti-forgery token in memory for the
 * life of the page. The session cookie itself is HttpOnly and unreadable from here, which produces
 * the asymmetry the console states on screen: after a reload the roster and the log can still be
 * read, while anything that changes something needs a fresh sign-in.
 *
 * WHY THIS IS A SEPARATE DOCUMENT rather than a tab inside one of the other three. It is a separate
 * audience — whoever administers a practice is not the owner reading their own backup, not the
 * clinician reading what a patient shared, and not the operator watching the process — and the
 * three existing surfaces are already split on exactly that line.
 */
import { mount } from 'svelte'
import './app.css'
import PracticeConsole from './lib/components/practice/PracticeConsole.svelte'

const target = document.getElementById('practice-app')
if (!target) throw new Error('#practice-app mount point missing')

const app = mount(PracticeConsole, { target })

export default app
