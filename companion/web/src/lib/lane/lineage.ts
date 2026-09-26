/*
 * WHICH LINEAGES ARE THE WEB CONSOLE'S LANES (#345).
 *
 * A lane is a lineage of the owner's own sync API (docs/SYNC_PROTOCOL.md §2) whose name begins
 * `lane_`. `GET /v1/snapshots` lists every lineage the owner has, so any console, and the phone,
 * finds every lane by that prefix alone, and nothing else on the server needs to know what a lane
 * is. A lane's own name is `lane_` and the URL-safe base64, without padding, of 16 random bytes: 27
 * characters, inside the server's [A-Za-z0-9_-]{1,64}. Every lineage beginning `lane_` is read as a
 * lane; the snapshot writer refuses to use such a name (sync/client.ts pushSnapshot), and the
 * enrolment anchor looks past them (recovery/serverKey.ts).
 *
 * No imports, so the sync client can ask without importing the lane.
 */

export const LANE_PREFIX = 'lane_'

/** The name a lane writer makes: the prefix, then 16 bytes' canonical URL-safe base64. */
export const LANE_LINEAGE = /^lane_[A-Za-z0-9_-]{21}[AQgw]$/

/** Whether a lineage is a lane, and so is read as one and never as a snapshot. */
export function isLaneLineage(lineage: string): boolean {
  return lineage.startsWith(LANE_PREFIX)
}
