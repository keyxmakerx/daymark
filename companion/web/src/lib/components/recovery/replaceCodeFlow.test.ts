import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/* The order the replace form keeps (#407): the key is opened before anything is sent, what was typed
   is dropped once used, and the open key is wiped whichever way it ends. */
const SRC = readFileSync(fileURLToPath(new URL('./NewCodeFlow.svelte', import.meta.url)), 'utf8')
const FN = SRC.slice(SRC.indexOf('async function replaceCode()'), SRC.indexOf('/** Read what the server holds'))

describe('the replace form', () => {
  it('positive control: the function body was found', () => {
    expect(FN).toContain('replaceRecoveryCodeOnServer')
  })

  it('opens the key before it sends anything, and refuses a mistyped code before any derivation', () => {
    const opens = FN.indexOf('unwrapWithPassphrase(held.wrapped')
    const sends = FN.indexOf('replaceRecoveryCodeOnServer(ports')
    expect(opens).toBeGreaterThan(-1)
    expect(sends).toBeGreaterThan(opens)
    expect(FN.indexOf('firstGroupProblem(groups)')).toBeLessThan(opens)
  })

  it('drops what was typed once it has been used, and wipes the open key in a finally', () => {
    const afterOpen = FN.slice(FN.indexOf("typedPassphrase = ''"))
    expect(afterOpen).toContain('groups = emptyGroups()')
    expect(afterOpen.indexOf("typedPassphrase = ''")).toBeLessThan(afterOpen.indexOf('replaceRecoveryCodeOnServer(ports'))
    expect(FN).toMatch(/finally \{\s*zeroizeDataKey\(master\)/)
  })

  it('shows the new code even when the stored version could not be checked, with its check beneath', () => {
    expect(FN).toContain("if (out.kind !== 'written') unread = out.sent")
    expect(FN).toContain("step = 'showing'")
  })
})
