/*
 * Sealing a share, as an order a test can hold (#275).
 *
 * WHY THIS IS A MODULE AND NOT COMPONENT CODE. What must stay true when an owner presses "Seal &
 * publish share" is an ORDER of effects, and an order can only be observed by something watching
 * the calls happen. Inside ShareBuilder.svelte it could be proved only by driving a browser; here,
 * against ports, it is a node test. pairing/ownerCeremony.ts makes the same argument for the same
 * reason. The ports do the work (the pin gate, the bundle, the seal, the upload); this function
 * owns only when each of them may run.
 *
 * THEY ENDED IT, AND NOTHING SENT NOW WOULD BE READ (#91, COMPANION_UX.md §7.8). The server is
 * asked whether the clinician ended the relationship FIRST: before their keys are pinned, before
 * the bundle is built, before anything is sealed. The refusal the owner then reads begins "Nothing
 * was sealed or sent", and it is literally true because nothing was started. The server refuses
 * the publish too, and that is the rule that binds, but a 410 from a route is not a sentence a
 * person can act on, and this is the moment the owner can still do something about it.
 *
 * A FAILED CHECK IS NOT AN ENDING. An unreachable server tells this console nothing about whether
 * the clinician left, and refusing to share on a timeout would stop somebody sending their journal
 * to a therapist who is perfectly well still there. So a check that throws lets the share go on;
 * if the relationship really has ended, the publish meets the server's own refusal and the screen
 * shows that instead.
 *
 * ONE VERSION, SIGNED AND PUBLISHED. The portal refuses a share whose signed version differs from
 * the one the server serves it under, so the version is looked up before anything is sealed and
 * the same number goes to the bundle, the seal and the upload.
 */

/** The server's half. Absent when no server is configured, and then nothing leaves the browser. */
export interface ShareSealServer<Sealed> {
  /** When the clinician ended the relationship, or null while it stands. A throw is a failed check. */
  relationshipEnding(): Promise<{ endedAt: number } | null>
  /** Versions already published on the share lineage. A throw reads as none. */
  listVersions(): Promise<readonly { version: number }[]>
  /** Uploads the sealed share as `version`. A throw is a share sealed here and not sent. */
  publish(sealed: Sealed, version: number): Promise<unknown>
}

/** Everything this function reaches outside itself for. See the header for why these are ports. */
export interface ShareSealPorts<Pins, Bundle, Sealed> {
  readonly server: ShareSealServer<Sealed> | null
  /** The pin gate: pins the clinician's keys on first use, writing storage, and returns the record. */
  pin(): Pins
  /** The bundle from the owner's own data, carrying the version it is published as. */
  build(version: number): Bundle
  /** Seals the bundle to the pinned clinician and signs it. Throws when the pins refuse the keys. */
  seal(bundle: Bundle, version: number, pins: Pins): Sealed
}

export type ShareSealOutcome =
  /** The clinician ended the relationship. Nothing was pinned, built, sealed or sent. */
  | { kind: 'ended'; endedAt: number }
  | { kind: 'published'; version: number }
  /** No server is configured: sealed in this browser and sent nowhere. */
  | { kind: 'sealed-locally'; version: number }

export async function sealShare<Pins, Bundle, Sealed>(
  ports: ShareSealPorts<Pins, Bundle, Sealed>,
): Promise<ShareSealOutcome> {
  const { server } = ports
  let version = 0
  if (server) {
    const ending = await server.relationshipEnding().catch(() => null)
    if (ending) return { kind: 'ended', endedAt: ending.endedAt }
    version = nextVersion(await server.listVersions().catch(() => []))
  }
  const pins = ports.pin()
  const sealed = ports.seal(ports.build(version), version, pins)
  if (!server) return { kind: 'sealed-locally', version }
  await server.publish(sealed, version)
  return { kind: 'published', version }
}

/** One past the highest version already published, or 0 when there is none. */
export function nextVersion(existing: readonly { version: number }[]): number {
  return existing.reduce((m, v) => Math.max(m, v.version), -1) + 1
}
