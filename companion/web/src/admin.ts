/*
 * The server admin console's entry point.
 *
 * Mounted with no props, deliberately. `baseUrl` defaults to '' because the three probes this
 * console reads — /healthz, /readyz and /v1/config — are registered at the server root rather than
 * under DAYMARK_BASE_PATH (Application.kt), so a relative fetch reaches them from wherever this
 * page is served.
 *
 * NO DIGEST IS SUPPLIED, and that is a stated limitation rather than an oversight. The chain check
 * therefore examines a pasted run for shape, ordering and uniqueness only; it treats entry hashes
 * as opaque strings and says nothing about whether a stored entry was rewritten. The console
 * reports this itself, in the run's own "not checked" list, so an operator cannot read the verdict
 * as more than it is. Supplying a real digest means hashing in the browser, where SubtleCrypto is
 * asynchronous while ChainInput.digest is synchronous — reshaping that contract is its own change,
 * not something to bolt on here.
 */
import { mount } from 'svelte'
import './app.css'
import AdminConsole from './lib/components/admin/AdminConsole.svelte'

const target = document.getElementById('admin-app')
if (!target) throw new Error('#admin-app mount point missing')

const app = mount(AdminConsole, { target })

export default app
