/*
 * The ui/ primitives, as one import.
 *
 * WHY A BARREL AT ALL. Phase 2 migrates 31 components off their hand-rolled <style> blocks and
 * onto these primitives. Every one of those files would otherwise carry five or six relative
 * paths that all say the same thing, and every future move of this directory would be a
 * 31-file diff. One entry point means a screen writes `import { Card, PageHeader, StatusPill }
 * from '$ui'`-shaped lines and stops caring where the files live.
 *
 * WHY THE TYPES COME THROUGH HERE TOO. A Svelte 5 instance script cannot export types, so the
 * shared vocabularies live in plain .ts files beside their components (nav.ts, status.ts,
 * table.ts). A caller that renders a DataTable needs `Column`, and one that renders a NavRail
 * needs `NavGroup`; making them reach past the barrel for those would mean the barrel had only
 * done half its job. `export type` rather than a bare re-export because the build runs
 * `verbatimModuleSyntax` — a type laundered through a value export is a build error, and
 * correctly so, since the emitted JS would import a module that has nothing in it.
 *
 * WHY STATUS_LABEL IS EXPORTED AND THE OTHER MAPS ARE NOT. It is the one runtime value in this
 * directory a caller legitimately needs without rendering a component: a table that sorts or
 * filters by lifecycle state has to spell the states the same way StatusPill does, and the
 * alternative is a second, drifting copy of the labels. Everything else here is reachable only
 * by rendering the component that owns it, which is the intent.
 *
 * This file is a re-export surface and nothing else. No component, no token, no logic — the
 * moment it grows a helper, the helper becomes something 31 files import transitively without
 * knowing they did.
 */

/* ---- Components ---------------------------------------------------------- */

export { default as AppShell } from './AppShell.svelte'
export { default as BandTag } from './BandTag.svelte'
export { default as Callout } from './Callout.svelte'
export { default as Card } from './Card.svelte'
export { default as Chip } from './Chip.svelte'
export { default as DataTable } from './DataTable.svelte'
export { default as EmptyState } from './EmptyState.svelte'
export { default as NavRail } from './NavRail.svelte'
export { default as PageHeader } from './PageHeader.svelte'
export { default as ProvenanceBadge } from './ProvenanceBadge.svelte'
export { default as StatusPill } from './StatusPill.svelte'

/* ---- Shared vocabularies -------------------------------------------------- */

/** The assignment lifecycle, and the one spelling of each state's human label. */
export { STATUS_LABEL } from './status'
export type { AssignmentStatus } from './status'

/** A table's column definitions — alignment and width belong to the column, not the cell. */
export type { Column } from './table'

/** The navigation's shape. `disabled` is a property, not an absence: the rail shows its limits. */
export type { NavItem, NavGroup } from './nav'
