/**
 * Labels for the server's own audit chain (#322): `admin-audit.db`, keyed "server", which records
 * the server being claimed and its administrators' sign-ins. It is a separate chain from every
 * relationship's log and from every practice's, so its lines are labelled here, for the server
 * console, and owner/auditLabels.test.ts holds every `server.*` action the server declares to this
 * list. `auth.success` and `lockout` are written here too, meaning an administrator's sign-in.
 */
const SERVER_LOG_LABELS: Record<string, string> = {
  'server.claimed': 'The server was claimed with its setup code',
  'server.setup_code_reissued': 'A new setup code was printed at the operator’s request',
  'auth.success': 'An administrator signed in',
  lockout: 'Sign-in paused for an administrator after repeated wrong codes',
}

/** The line for [action] on the server's own log; an unknown code is shown as itself, never hidden. */
export function serverLogActionLabel(action: string): string {
  return SERVER_LOG_LABELS[action] ?? action
}
