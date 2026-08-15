/*
 * A table's column definitions, as one shared shape.
 *
 * WHY THIS IS A PLAIN .ts FILE. A Svelte 5 instance script cannot export types — `<script
 * lang="ts">` compiles to a component instance, not a module — so the interface that both
 * DataTable and every one of its callers needs has to live next to the component rather than
 * inside it. Same arrangement as status.ts beside StatusPill.
 *
 * WHY `numeric` IS PART OF THE COLUMN AND NOT THE CELL. Alignment is a property of the whole
 * column, not of one value: a scores column where 9 and 12 are set in proportional digits does
 * not line up, and a reader scanning it vertically is comparing glyph widths instead of
 * magnitudes. Declaring it once on the column means the header, every present row and every
 * row added later agree — a cell cannot opt out and quietly break the alignment of the column
 * it sits in. `numeric` is the right flag for dates and IDs too: anything read by position
 * rather than as prose.
 *
 * WHY `width` IS A STRING. It is passed through to CSS verbatim (`12ch`, `20%`, `8rem`), so a
 * caller can pin the narrow columns and let the prose column take the slack. It is optional
 * because the honest default is "let the content decide".
 *
 * `key` indexes into the row record and is also what DataTable hands to a `cell` snippet, so a
 * caller can switch on it to render a StatusPill or a BandTag for one column only.
 */

export interface Column {
  key: string
  label: string
  numeric?: boolean
  width?: string
}
