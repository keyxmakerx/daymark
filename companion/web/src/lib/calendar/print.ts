/*
 * PRINTING THE MONTH, as ports and transitions (#335).
 *
 * "Print this month" puts the month on paper as it is: ink on white, every check-in's square
 * keeping its word, and nothing else from the page — not the day panel, not the controls, not the
 * rest of the console. Two things make that true, and their ORDER is the whole of this file:
 *
 *   1. The page is switched to the light theme for the length of the print, so the paper gets
 *      dark ink on white whatever theme the screen is in. It is the theme switch the app already
 *      has (`data-theme`, app.css), used the way the server console uses it (admin/AdminConsole.svelte),
 *      with no second palette to keep in step.
 *   2. The root is flagged as printing the month. MonthCalendar.svelte's print stylesheet reads the
 *      flag and leaves off the paper everything that neither is the month, holds it, nor sits
 *      inside it; its own print rules leave off the day panel and the controls. Printing the page
 *      any other way, without the flag, prints the page as it always has.
 *
 * Both are set before the print dialog opens, and both are put back as they were once it closes —
 * a theme the person chose stays chosen. Nothing here reads the clock or the network, and the
 * browser's own pieces come in through a port, so the order is a node test (print.test.ts).
 */

/** Set on the document's root while the month is printing. */
export const PRINT_ATTRIBUTE = 'data-printing'
export const PRINT_VALUE = 'month'

/** Marks the element that is the month, so the print stylesheet can keep it and leave the rest off. */
export const PRINT_ROOT_ATTRIBUTE = 'data-print-root'

export interface PrintPort {
  /** The document's root element, where the theme and the printing flag are set. */
  root: Pick<Element, 'getAttribute' | 'setAttribute' | 'removeAttribute'>
  /** Calls `then` once, after the print dialog has closed. */
  afterPrint(then: () => void): void
  /** Opens the print dialog. */
  print(): void
}

/** Prints the month: light theme and the printing flag first, the dialog, then both put back. */
export function printMonth(port: PrintPort): void {
  // A print still open (its dialog never reported closing) already holds the page as it was;
  // reading the theme again now would read the light theme this set, and keep it for good.
  if (port.root.getAttribute(PRINT_ATTRIBUTE) === PRINT_VALUE) {
    port.print()
    return
  }
  const theme = port.root.getAttribute('data-theme')
  port.root.setAttribute('data-theme', 'light')
  port.root.setAttribute(PRINT_ATTRIBUTE, PRINT_VALUE)
  port.afterPrint(() => {
    port.root.removeAttribute(PRINT_ATTRIBUTE)
    if (theme === null) port.root.removeAttribute('data-theme')
    else port.root.setAttribute('data-theme', theme)
  })
  port.print()
}

/** The browser's own pieces. */
export function browserPrintPort(): PrintPort {
  return {
    root: document.documentElement,
    afterPrint: (then) => window.addEventListener('afterprint', then, { once: true }),
    print: () => window.print(),
  }
}
