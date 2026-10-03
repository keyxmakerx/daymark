/*
 * PRINTING IN INK ON WHITE, as ports and transitions (#335, #417).
 *
 * Two things print from the web, and both must reach the paper as dark ink on white whatever theme
 * the screen is in: the person's month ("Print this month") and the recovery code sheet ("Print
 * this page" beside the code). Printed from the night theme without this, the night theme's light
 * ink can reach white paper, and a recovery code printed pale may not be readable when it is needed.
 *
 *   1. The page is switched to the light theme for the length of the print. It is the theme switch
 *      the app already has (`data-theme`, app.css), used the way the server console uses it
 *      (admin/AdminConsole.svelte), with no second palette to keep in step.
 *   2. The root is flagged with what is printing. For the month, MonthCalendar.svelte's print
 *      stylesheet reads the flag and leaves off the paper everything that neither is the month,
 *      holds it, nor sits inside it; its own print rules leave off the day panel and the controls.
 *      The code sheet's page prints as it stands, its own print rules leaving off its controls, so
 *      its flag is read by no stylesheet: it marks a print as open, which step 3 needs. Printing
 *      any other way, without a flag, prints the page as it always has.
 *   3. Both are set before the print dialog opens, and both are put back as they were once it
 *      closes — a theme the person chose stays chosen. A print asked for while one is still open
 *      changes nothing, so the theme put back is still the person's.
 *
 * Nothing here reads the clock or the network, and the browser's own pieces come in through a
 * port, so the order is a node test (print.test.ts).
 */

/** Set on the document's root while something is printing, naming what. */
export const PRINT_ATTRIBUTE = 'data-printing'
/** The month is printing: MonthCalendar.svelte's print stylesheet leaves the rest of the page off. */
export const PRINT_VALUE = 'month'
/** The recovery code sheet's page is printing, as it stands (#417). */
export const PRINT_CODE_SHEET = 'recovery-code'

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

/** Prints in the light theme, flagged with what is printing: both set first, the dialog, then both put back. */
function printInLight(port: PrintPort, what: string): void {
  // A print still open (its dialog never reported closing) already holds the page as it was;
  // reading the theme again now would read the light theme this set, and keep it for good.
  if (port.root.getAttribute(PRINT_ATTRIBUTE) !== null) {
    port.print()
    return
  }
  const theme = port.root.getAttribute('data-theme')
  port.root.setAttribute('data-theme', 'light')
  port.root.setAttribute(PRINT_ATTRIBUTE, what)
  port.afterPrint(() => {
    port.root.removeAttribute(PRINT_ATTRIBUTE)
    if (theme === null) port.root.removeAttribute('data-theme')
    else port.root.setAttribute('data-theme', theme)
  })
  port.print()
}

/** Prints the month alone, ink on white. */
export function printMonth(port: PrintPort): void {
  printInLight(port, PRINT_VALUE)
}

/** Prints the page the recovery code sheet is on, ink on white (#417). */
export function printCodeSheet(port: PrintPort): void {
  printInLight(port, PRINT_CODE_SHEET)
}

/** The browser's own pieces. */
export function browserPrintPort(): PrintPort {
  return {
    root: document.documentElement,
    afterPrint: (then) => window.addEventListener('afterprint', then, { once: true }),
    print: () => window.print(),
  }
}
