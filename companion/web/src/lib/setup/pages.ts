/*
 * WHICH OF THIS BUNDLE'S PAGES THE SERVER SERVES, AND SO WHAT THE OWNER'S PAGE MAY POINT AT (#330).
 *
 * The server's shape decides which pages it serves (SetupMode.kt, and `Pages` in Application.kt):
 * the owner's page and the server console in every shape, the clinician's page in paired and
 * practice, the practice page in practice only. A page its shape leaves off answers 403 with no
 * body, so a link to it from the owner's page would open an empty refusal, and a sentence
 * pointing at it would name a page this server does not have.
 *
 * A PAGE AND ITS ROUTES ARE SWITCHED TOGETHER. The clinician's page comes with the clinician
 * group of routes — invitations, pairing, relationships, grants, shares — and the practice page
 * with the practice routes; where a shape leaves a page off, its routes answer 503. So the same
 * table decides an entry point on the owner's page that calls those routes: the owner console,
 * which is for clinicians and shares, is offered where the clinician's page is.
 *
 * WHAT THE OWNER'S PAGE CAN KNOW: only what the server published. When `/v1/config` carries a
 * `setupMode`, the shape is known and the owner's page points only at what that shape serves.
 * When it carries none — the operator chose no shape, the request never answered, or this browser
 * already held an answer and so asked nothing (shape.ts, [shouldReadConfiguration]) — the page
 * cannot tell what the server serves, and everything stays. A person's own answer to the
 * first-run question is not evidence either way: it routes this page and changes nothing on the
 * server.
 *
 * The table restates the server's, so pages.test.ts checks it against both tables the server's own
 * test proves over real responses, its pages and its route groups (ShapeRoutingTest.kt), rather
 * than trusting them to agree.
 *
 * PURE, and with no runtime import. shape.ts imports audience.ts and audience.ts imports this, so
 * reaching back into shape.ts for anything but a type would close a cycle.
 */
import type { ShapeId } from './shape'

/**
 * The pages some shape refuses, each with the routes that come with it. The owner's page and the
 * server console are in every shape.
 */
export type ShapePage = 'clinician' | 'practice'

/** The shapes that serve each of those pages, and switch its routes on. */
export const SHAPES_SERVING: Readonly<Record<ShapePage, readonly ShapeId[]>> = {
  clinician: ['paired', 'practice'],
  practice: ['practice'],
}

/**
 * Whether the owner's page may point at [page]: link it, name it in a sentence, or offer an entry
 * point that calls the routes that come with it.
 *
 * With a published shape: only when that shape serves the page. With none: always, because this
 * page cannot tell which pages the server serves, and withholding one it does serve would leave
 * somebody who belongs there with no way to find it.
 */
export function linksTo(page: ShapePage, published: ShapeId | null): boolean {
  return published === null || SHAPES_SERVING[page].includes(published)
}
