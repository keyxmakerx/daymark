/*
 * The recovery-code surface, as one import.
 *
 * WHY A BARREL FOR ONE SCREEN. The same reason ui/index.ts has one: the panel is composed of six
 * files that only make sense together, and a caller wiring it into a page has no business knowing
 * that the write-down check is a separate component from the sheet it hides. One entry point means
 * the internal arrangement can change without touching whatever mounted it.
 *
 * WHY THE COPY AND THE LOGIC ARE EXPORTED TOO. Both are read by tests that assert over this surface
 * without rendering it — the suite runs in node, where there is no component renderer — and by
 * nothing else. Exporting them here rather than making the tests reach past the barrel keeps one
 * public shape for the directory.
 *
 * This file is a re-export surface and nothing else. The moment it grows a helper, the helper
 * becomes something every importer pulls in transitively without knowing it did.
 */

/* ---- The screen ----------------------------------------------------------- */

export { default as RecoveryPanel } from './RecoveryPanel.svelte'
export { default as CodeSheet } from './CodeSheet.svelte'
export { default as GroupEntry } from './GroupEntry.svelte'
export { default as WriteDownCheck } from './WriteDownCheck.svelte'
export { default as NewCodeFlow } from './NewCodeFlow.svelte'
export { default as UseCodeFlow } from './UseCodeFlow.svelte'
export { default as Placeholder } from './Placeholder.svelte'

/* ---- The logic behind it -------------------------------------------------- */

export {
  GROUP_COUNT,
  codeFromGroups,
  distributeIntoGroups,
  emptyGroups,
  firstGroupProblem,
  groupOfPosition,
  groupsAreEmpty,
  groupsToTyped,
  normalizeGroup,
} from './groups'
export type { GroupProblem } from './groups'

export {
  CONFIRMATION_GROUPS,
  checkWrittenDown,
  chooseConfirmationGroups,
  confirmationIsComplete,
  firstMismatch,
  groupOfCode,
  unitRandom,
} from './confirmation'
export type { ConfirmationResult } from './confirmation'

export {
  FILE_NOTE,
  STAND_IN_MARKER,
  WRAPPED_KEY_FILE_FAULT_TEXT,
  decodeWrappedKeyFile,
  encodeWrappedKeyFile,
  heldWrappedKey,
  holdWrappedKey,
  releaseWrappedKey,
  slotSummary,
} from './session'
export type { WrappedKeyFile, WrappedKeyFileFault, WrappedKeyFileRead } from './session'
