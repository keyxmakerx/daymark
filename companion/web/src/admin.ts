/*
 * The server admin console's entry point.
 *
 * Mounted with no props, deliberately. `baseUrl` defaults to '' because the three probes this
 * console reads — /healthz, /readyz and /v1/config — are registered at the server root rather than
 * under DAYMARK_BASE_PATH (Application.kt), so a relative fetch reaches them from wherever this
 * page is served.
 *
 * THE DIGEST IS REAL NOW (task #16). AdminConsole defaults its `digest` prop to
 * lib/admin/sha256.ts — a synchronous, dependency-free SHA-256 proven against the published
 * vectors and node's own implementation in its suite — so a pasted run has its entry hashes
 * actually recomputed instead of being treated as opaque strings. The default lives on the
 * component rather than being passed here, so any mount of the console gets the real check
 * without this file having to remember to hand it over. What has NOT changed: recomputation
 * still establishes internal consistency only, and the run's own "not checked" list still says
 * so — a server that declines to append, or truncates, produces a run that recomputes perfectly.
 */
import { mount } from 'svelte'
import './app.css'
import AdminConsole from './lib/components/admin/AdminConsole.svelte'

const target = document.getElementById('admin-app')
if (!target) throw new Error('#admin-app mount point missing')

const app = mount(AdminConsole, { target })

export default app
