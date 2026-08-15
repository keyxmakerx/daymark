/*
 * The assignment lifecycle, as one shared vocabulary.
 *
 * WHY THIS IS A PLAIN .ts FILE. A Svelte 5 instance script cannot export types — `<script
 * lang="ts">` compiles to a component instance, not a module — so a union that both
 * StatusPill and its callers need has to live next to the component rather than inside it.
 * Everything that renders or reasons about assignment state imports from here, so there is
 * exactly one list of what can happen to an assignment and exactly one spelling of each
 * state's human label.
 *
 * WHY THE LABELS LIVE HERE TOO. If each call site wrote its own string we would drift into
 * "No reply" / "No response" / "Unanswered" for one machine state, and a therapist reading
 * two screens would reasonably conclude they were looking at two different things. The map
 * is Record<AssignmentStatus, string>, so adding a state to the union without giving it a
 * label is a type error rather than a blank pill.
 *
 * A note on the states themselves: 'noResponse' and 'declined' are deliberately distinct.
 * Silence is not refusal. Conflating them would let the UI report a decision the person
 * never made.
 */

export type AssignmentStatus =
  | 'scheduled'
  | 'delivered'
  | 'accepted'
  | 'completed'
  | 'declined'
  | 'noResponse'
  | 'overdue'

export const STATUS_LABEL: Record<AssignmentStatus, string> = {
  scheduled: 'Scheduled',
  delivered: 'Delivered',
  accepted: 'Accepted',
  completed: 'Completed',
  declined: 'Declined',
  noResponse: 'No response',
  overdue: 'Overdue',
}
