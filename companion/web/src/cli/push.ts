/*
 * daymark-sync push — reference writer (runs on your laptop until the phone Sync flavor
 * ships). Encrypts a Daymark backup JSON with your sync passphrase and PUTs it to your
 * Companion as the next append-only version. The server only ever sees ciphertext.
 *
 * Usage:
 *   DAYMARK_SYNC_PASSPHRASE='…' DAYMARK_AUTH_TOKEN='…' pnpm --silent push -- \
 *     --server http://localhost:8080 --lineage laptop --backup backup.json
 *
 * THE SECRETS COME FROM THE ENVIRONMENT, NEVER THE COMMAND LINE. The passphrase is read from
 * DAYMARK_SYNC_PASSPHRASE and the server's access token from DAYMARK_AUTH_TOKEN. A process's
 * arguments can be read by other users of the same machine while it runs (`ps`, /proc), and pnpm
 * prints them unless `--silent` is given, so a `--token` argument is refused before anything is
 * sent, and the refusal never repeats it (#384).
 *
 * The snapshot is padded before it is encrypted (#315). If the padded snapshot is larger than
 * --max-blob-bytes (default: the server's default limit, 25 MiB), nothing is sent and the reason is
 * printed; it is never sent unpadded instead. Pass --max-blob-bytes when your server's operator has
 * set DAYMARK_MAX_BLOB_BYTES to something larger.
 *
 * WHAT RUNS IT. `pnpm push` runs this file with vite-node, pinned to the version vitest uses, so it
 * resolves modules exactly as the bundle and the test suite do: through vite.config.ts, whose alias
 * points libsodium-wrappers-sumo at its CommonJS build. Plain node and tsx do not read that alias,
 * and the package's ESM build imports a file it does not ship, so under them this writer died
 * before its first line (#373). src/cli/push.test.ts spawns `pnpm push` itself and fails if it
 * cannot start. `--help` prints the usage below and sends nothing.
 */
import { readFileSync } from 'node:fs'
import { DEFAULT_MAX_BLOB_BYTES, LANE_IS_NOT_A_SNAPSHOT, SnapshotTooLargeError, SyncClient } from '../lib/sync/client'
import { isLaneLineage } from '../lib/lane/lineage'

const USAGE = [
  'Usage:',
  "  DAYMARK_SYNC_PASSPHRASE='…' DAYMARK_AUTH_TOKEN='…' pnpm --silent push -- --server <url> --lineage <name> --backup <file.json>",
  '',
  'Encrypts a Daymark backup export with your sync passphrase and uploads it to your Companion as the',
  'next version. The server only ever receives ciphertext. The passphrase is read from',
  'DAYMARK_SYNC_PASSPHRASE and the server access token from DAYMARK_AUTH_TOKEN, never from the',
  'command line, where other users of this machine could read them.',
  '',
  '  --server <url>          your Companion (default http://localhost:8080)',
  '  --lineage <name>        the name this copy is filed under, 1 to 64 of A-Z a-z 0-9 _ - (default laptop),',
  '                          never beginning lane_, which names the web console\'s lanes',
  '  --backup <file.json>    the backup exported from the app',
  '  --max-blob-bytes <n>    the largest snapshot your server accepts (default 26214400, the server default)',
  '  --help                  print this and send nothing',
].join('\n')

function arg(name: string): string | undefined {
  const i = process.argv.indexOf(`--${name}`)
  return i >= 0 ? process.argv[i + 1] : undefined
}

async function main() {
  if (process.argv.includes('--help')) {
    process.stdout.write(`${USAGE}\n`)
    return
  }
  if (process.argv.includes('--token')) {
    throw new Error(
      'the access token is read from DAYMARK_AUTH_TOKEN, never from the command line, where other ' +
        'users of this machine could read it. Nothing was sent.',
    )
  }
  const server = arg('server') ?? 'http://localhost:8080'
  const token = process.env.DAYMARK_AUTH_TOKEN
  const lineage = arg('lineage') ?? 'laptop'
  const backupPath = arg('backup')
  const passphrase = process.env.DAYMARK_SYNC_PASSPHRASE

  const maxBlobArg = arg('max-blob-bytes')

  if (!token) throw new Error('set DAYMARK_AUTH_TOKEN in the environment (the server access token)')
  if (!backupPath) throw new Error('missing --backup <path to a Daymark backup .json>')
  if (!passphrase) throw new Error('set DAYMARK_SYNC_PASSPHRASE in the environment')
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(lineage)) throw new Error('--lineage must be 1–64 chars of [A-Za-z0-9_-]')
  // A lane is where the web console keeps what it adds (#345): never filed as a snapshot.
  if (isLaneLineage(lineage)) throw new Error(LANE_IS_NOT_A_SNAPSHOT)
  if (maxBlobArg !== undefined && !/^[1-9][0-9]{0,14}$/.test(maxBlobArg)) {
    throw new Error('--max-blob-bytes must be a whole number of bytes, such as 26214400')
  }
  const maxBlobBytes = maxBlobArg === undefined ? DEFAULT_MAX_BLOB_BYTES : Number(maxBlobArg)

  const plaintext = readFileSync(backupPath)
  // Validate it is JSON before encrypting (fail early on the wrong file).
  try {
    JSON.parse(plaintext.toString('utf8'))
  } catch {
    throw new Error(`${backupPath} is not valid JSON — expected a Daymark backup export`)
  }

  const client = new SyncClient(server, token, undefined, { maxBlobBytes })
  // Before the first request, so that a refusal's "Nothing was sent" is true of this whole run.
  client.assertSnapshotFits(plaintext.length)
  const existing = await client.listVersions(lineage).catch(() => [])
  const nextVersion = existing.length ? Math.max(...existing.map((v) => v.version)) + 1 : 0

  process.stdout.write(`Encrypting ${backupPath} → ${server} as ${lineage} v${nextVersion} …\n`)
  const meta = await client.pushSnapshot(lineage, nextVersion, new Uint8Array(plaintext), passphrase)
  process.stdout.write(`Pushed ${meta.size} bytes (sha256 ${meta.contentHash.slice(0, 16)}…). The server cannot read it.\n`)
}

main().catch((e) => {
  process.stderr.write(`push failed: ${e instanceof Error ? e.message : String(e)}\n`)
  if (e instanceof SnapshotTooLargeError && e.limitBytes !== null) {
    process.stderr.write(
      'If your server accepts larger snapshots (its DAYMARK_MAX_BLOB_BYTES), run this again with ' +
        '--max-blob-bytes set to the same number.\n',
    )
  }
  process.exit(1)
})
