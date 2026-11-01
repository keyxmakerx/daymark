/*
 * GETTING BACK IN WITH THE RECOVERY CODE (#325): the order of the three steps, as a node test.
 *
 * The Recover card (components/owner/RecoverAccess.svelte) takes the recovery code, a new name and
 * a new authenticator, and hands them here. This asks the server for a challenge, opens the wrapped
 * key's recovery slot with the code and signs the challenge with the recovery key (recoveryKey.ts),
 * then sends the signature with the new name and authenticator.
 *
 * WHAT MUST STAY TRUE.
 * - THE CODE NEVER LEAVES THE PAGE. Nothing sent carries it: the server is sent a signature, a
 *   name, the new authenticator key and six digits, and nothing else.
 * - A CODE THAT DOES NOT OPEN THE KEY STOPS HERE, before anything is sent that could change the
 *   sign-in, with the same sentence the passphrase-recovery screen uses (CODE_DOES_NOT_OPEN_THIS).
 * - THE ADDRESS SIGNED FOR IS THIS PAGE'S OWN ORIGIN, passed in by the caller, never one the server
 *   names (recoveryKey.ts says why).
 * - Every refusal is a fixed sentence from account.ts, chosen by status, never the server's text.
 */
import type { Outcome, SignedIn } from './account'
import type { RecoveryChallenge } from './recoveryKey'
import { CODE_DOES_NOT_OPEN_THIS } from '../components/recovery/copy'

/** What the steps need: the two account calls and the proof, so a test can watch the order. */
export interface RecoverPorts {
  recoveryChallenge(): Promise<Outcome<RecoveryChallenge>>
  prove(challenge: RecoveryChallenge, typedCode: string, address: string): Promise<string>
  recover(req: { challengeId: string; signature: string; name: string; seed: Uint8Array; totpCode: string }): Promise<Outcome<SignedIn>>
}

export interface RecoverInputs {
  typedCode: string
  /** This page's origin: the address the proof is bound to. */
  address: string
  name: string
  seed: Uint8Array
  totpCode: string
}

export type RecoverResult =
  | { ok: true; name: string }
  /** Where it stopped: before the challenge, at the code, or at the replacement. */
  | { ok: false; stage: 'challenge' | 'code' | 'recover'; status: number | null; message: string }

export async function recoverSignIn(ports: RecoverPorts, inputs: RecoverInputs): Promise<RecoverResult> {
  const challenge = await ports.recoveryChallenge()
  if (!challenge.ok) return { ok: false, stage: 'challenge', status: challenge.status, message: challenge.message }
  let signature: string
  try {
    signature = await ports.prove(challenge.value, inputs.typedCode, inputs.address)
  } catch {
    return { ok: false, stage: 'code', status: null, message: CODE_DOES_NOT_OPEN_THIS }
  }
  const out = await ports.recover({
    challengeId: challenge.value.challengeId,
    signature,
    name: inputs.name,
    seed: inputs.seed,
    totpCode: inputs.totpCode,
  })
  if (!out.ok) return { ok: false, stage: 'recover', status: out.status, message: out.message }
  return { ok: true, name: out.value.name }
}
