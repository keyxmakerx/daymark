/*
 * WHICH OF THIS BUNDLE'S PAGES THE SERVER SERVES, AND SO WHICH THE OWNER'S PAGE MAY LINK (#330).
 *
 * The server's shape decides which pages it serves (SetupMode.kt, and `Pages` in Application.kt):
 * the owner's page and the server console in every shape, the clinician's page in paired and
 * practice, the practice page in practice only. A page its shape leaves off answers 403 with no
 * body, so a link to it from the owner's page would open an empty refusal, and a sentence
 * pointing at it would name a page this server does not have.
 *
 * WHAT THE OWNER'S PAGE CAN KNOW: only what the server published. When `/v1/config` carries a
 * `setupMode`, the shape is known and only that shape's pages are linked. When it carries none —
 * the operator chose no shape, the request never answered, or this browser already held an answer
 * and so asked nothing (shape.ts, [shouldReadConfiguration]) — the page cannot tell what the
 * server serves, and every link stays. A person's own answer to the first-run question is not
 * evidence either way: it routes this page and changes nothing on the server.
 *
 * The table restates the server's, so pages.test.ts checks it against the table the server's own
 * test proves over real responses (ShapeRoutingTest.kt) rather than trusting the two to agree.
 *
 * PURE, and with no runtime import. shape.ts imports audience.ts and audience.ts imports this, so
 * reaching back into shape.ts for anything but a type would close a cycle.
 */
import type { ShapeId } from './shape'

/** The pages some shape refuses. The owner's page and the server console are in every shape. */
export type ShapePage = 'clinician' | 'practice'

/** The shapes that serve each of those pages. */
export const SHAPES_SERVING: Readonly<Record<ShapePage, readonly ShapeId[]>> = {
  clinician: ['paired', 'practice'],
  practice: ['practice'],
}

/**
 * Whether the owner's page may link [page], or point at it in a sentence.
 *
 * With a published shape: only when that shape serves the page. With none: always, because this
 * page cannot tell which pages the server serves, and withholding one it does serve would leave
 * somebody who belongs there with no way to find it.
 */
export function linksTo(page: ShapePage, published: ShapeId | null): boolean {
  return published === null || SHAPES_SERVING[page].includes(published)
}
