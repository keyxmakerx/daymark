/*
 * daymark-sync push — reference writer (runs on your laptop until the phone Sync flavor
 * ships). Encrypts a Daymark backup JSON with your sync passphrase and PUTs it to your
 * Companion as the next append-only version. The server only ever sees ciphertext.
 *
 * Usage:
 *   DAYMARK_SYNC_PASSPHRASE='…' pnpm push -- \
 *     --server http://localhost:8080 --token "$TOKEN" --lineage laptop --backup backup.json
 *
 * The passphrase is read from DAYMARK_SYNC_PASSPHRASE (never passed on the command line).
 */
import { readFileSync } from 'node:fs'
import { SyncClient } from '../lib/sync/client'

function arg(name: string): string | undefined {
  const i = process.argv.indexOf(`--${name}`)
  return i >= 0 ? process.argv[i + 1] : undefined
}

async function main() {
  const server = arg('server') ?? 'http://localhost:8080'
  const token = arg('token')
  const lineage = arg('lineage') ?? 'laptop'
  const backupPath = arg('backup')
  const passphrase = process.env.DAYMARK_SYNC_PASSPHRASE

  if (!token) throw new Error('missing --token (the server access token)')
  if (!backupPath) throw new Error('missing --backup <path to a Daymark backup .json>')
  if (!passphrase) throw new Error('set DAYMARK_SYNC_PASSPHRASE in the environment')
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(lineage)) throw new Error('--lineage must be 1–64 chars of [A-Za-z0-9_-]')

  const plaintext = readFileSync(backupPath)
  // Validate it is JSON before encrypting (fail early on the wrong file).
  try {
    JSON.parse(plaintext.toString('utf8'))
  } catch {
    throw new Error(`${backupPath} is not valid JSON — expected a Daymark backup export`)
  }

  const client = new SyncClient(server, token)
  const existing = await client.listVersions(lineage).catch(() => [])
  const nextVersion = existing.length ? Math.max(...existing.map((v) => v.version)) + 1 : 0

  process.stdout.write(`Encrypting ${backupPath} → ${server} as ${lineage} v${nextVersion} …\n`)
  const meta = await client.pushSnapshot(lineage, nextVersion, new Uint8Array(plaintext), passphrase)
  process.stdout.write(`Pushed ${meta.size} bytes (sha256 ${meta.contentHash.slice(0, 16)}…). The server cannot read it.\n`)
}

main().catch((e) => {
  process.stderr.write(`push failed: ${e instanceof Error ? e.message : String(e)}\n`)
  process.exit(1)
})
