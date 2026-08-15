/*
 * The navigation's shape, as one shared vocabulary.
 *
 * WHY THIS IS A PLAIN .ts FILE. A Svelte 5 instance script cannot export types — `<script
 * lang="ts">` compiles to a component instance, not a module — so the interfaces that both
 * NavRail and every screen that builds a nav need have to live next to the component rather
 * than inside it. Same arrangement as status.ts beside StatusPill and table.ts beside
 * DataTable.
 *
 * WHY `disabled` IS A PROPERTY AND NOT AN ABSENCE. The obvious way to express "this person
 * cannot open Shared data" is to leave the item out of the array. This product does the
 * opposite on purpose. A rail that silently omits what a role cannot reach teaches every
 * reader a different, smaller shape of the application, so nobody can tell the difference
 * between a feature that does not exist, a feature they have not been granted, and a feature
 * that was quietly removed. Showing the boundary — dimmed, announced as `aria-disabled`, still
 * reachable by keyboard — means the answer to "why can't I see X?" is on screen instead of in
 * a support thread. It also keeps the product honest with the person whose data it is: the
 * limits of a staff account should be visible, not invisible.
 *
 * WHY `count` IS OPTIONAL AND WHY 0 IS NOT THE SAME AS UNDEFINED. `undefined` means "this
 * destination does not report a quantity" and renders nothing. `0` means "we counted, and
 * there is nothing waiting" — a real, useful statement, and one the rail renders rather than
 * hides. Collapsing the two would make a queue that just emptied look like a queue that was
 * never measured, and would make the row twitch as the number crossed zero.
 *
 * WHY `critical` IS A BOOLEAN AND NOT A COLOUR. The call site says what the count MEANS —
 * something here needs a human — and NavRail decides how that is drawn. A call site that could
 * pass a hue could invent a second alarm colour, and the moment there are two reds neither
 * means anything (app.css, invariant 2). There is deliberately no inverse flag: `critical:
 * false` is the ordinary state and draws no reassurance, because there is no green in this
 * system and a rail is in no position to certify that a queue is fine.
 *
 * `id` is the caller's own routing key. NavRail never parses it, only compares it to `active`
 * and hands it back through `onselect`, so it can be a route, a tab name or an opaque token.
 */

export interface NavItem {
  id: string
  label: string
  count?: number
  critical?: boolean
  disabled?: boolean
}

/*
 * A labelled run of items. `label` is optional because the first group is usually the
 * unlabelled trunk of the application ("Today", "People", "Instruments") and inventing a
 * heading for it — "Main", "General" — adds a word that carries no information.
 */
export interface NavGroup {
  label?: string
  items: NavItem[]
}
