/*
 * Printing the month (#335): the order of the steps, as a node test.
 *
 * The paper is ink on white and holds the month alone only if the theme and the printing flag are
 * set BEFORE the dialog opens, and the page is only left as the person had it if both are put back
 * AFTER it closes. A port that records every call makes that order an assertion.
 */

import { describe, it, expect } from 'vitest'
import { PRINT_ATTRIBUTE, PRINT_ROOT_ATTRIBUTE, PRINT_VALUE, printMonth, type PrintPort } from './print'

interface Recorder {
  port: PrintPort
  calls: string[]
  attributes: Map<string, string>
  closeDialog: () => void
}

/** A root element and a dialog that write down everything done to them, in order. */
function recorder(initial: Record<string, string> = {}): Recorder {
  const calls: string[] = []
  const attributes = new Map(Object.entries(initial))
  const pending: (() => void)[] = []
  const port: PrintPort = {
    root: {
      getAttribute: (name) => attributes.get(name) ?? null,
      setAttribute: (name, value) => {
        calls.push(`set ${name}=${value}`)
        attributes.set(name, value)
      },
      removeAttribute: (name) => {
        calls.push(`remove ${name}`)
        attributes.delete(name)
      },
    },
    afterPrint: (then) => {
      calls.push('listen afterprint')
      pending.push(then)
    },
    print: () => {
      calls.push('print')
    },
  }
  const closeDialog = () => {
    calls.push('dialog closed')
    for (const then of pending.splice(0)) then()
  }
  return { port, calls, attributes, closeDialog }
}

describe('printing the month', () => {
  it('sets the light theme and the printing flag before the dialog opens', () => {
    const r = recorder({ 'data-theme': 'dark' })
    printMonth(r.port)
    expect(r.calls).toEqual([`set data-theme=light`, `set ${PRINT_ATTRIBUTE}=${PRINT_VALUE}`, 'listen afterprint', 'print'])
    // While the dialog is open, the page is the light theme and flagged as printing the month.
    expect(r.attributes.get('data-theme')).toBe('light')
    expect(r.attributes.get(PRINT_ATTRIBUTE)).toBe(PRINT_VALUE)
  })

  it('puts back the theme the person chose once the dialog closes', () => {
    const r = recorder({ 'data-theme': 'dark' })
    printMonth(r.port)
    r.closeDialog()
    expect(r.calls.slice(-3)).toEqual(['dialog closed', `remove ${PRINT_ATTRIBUTE}`, 'set data-theme=dark'])
    expect(Object.fromEntries(r.attributes)).toEqual({ 'data-theme': 'dark' })
  })

  it('leaves no theme set where none was, so the system’s own choice stands again', () => {
    const r = recorder()
    printMonth(r.port)
    expect(r.attributes.get('data-theme')).toBe('light')
    r.closeDialog()
    expect(Object.fromEntries(r.attributes)).toEqual({})
    expect(r.calls.slice(-2)).toEqual([`remove ${PRINT_ATTRIBUTE}`, 'remove data-theme'])
  })

  it('a second print while one is still open keeps the person’s theme to put back', () => {
    const r = recorder({ 'data-theme': 'dark' })
    printMonth(r.port)
    printMonth(r.port) // the first dialog never reported closing
    // The second did not read the light theme the first set, and set nothing of its own.
    expect(r.calls).toEqual([`set data-theme=light`, `set ${PRINT_ATTRIBUTE}=${PRINT_VALUE}`, 'listen afterprint', 'print', 'print'])
    r.closeDialog()
    expect(Object.fromEntries(r.attributes)).toEqual({ 'data-theme': 'dark' })
  })

  it('names the print root the month is marked with, and it is not the flag', () => {
    expect(PRINT_ROOT_ATTRIBUTE).toBe('data-print-root')
    expect(PRINT_ROOT_ATTRIBUTE).not.toBe(PRINT_ATTRIBUTE)
  })
})
