/*
 * The server admin console's entry point.
 *
 * The gate mounts first (#322): the claim screen on a new server, sign-in otherwise, and the
 * console only to a signed-in administrator. Mounted with no props, deliberately: `baseUrl`
 * defaults to '' because everything this page reads (/healthz, /readyz, /v1/config and the
 * /v1/admin routes) is registered at the server root rather than under DAYMARK_BASE_PATH
 * (Application.kt), so a relative fetch reaches it from wherever this page is served.
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
import AdminGate from './lib/components/admin/AdminGate.svelte'

const target = document.getElementById('admin-app')
if (!target) throw new Error('#admin-app mount point missing')

const app = mount(AdminGate, { target })

export default app
