import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { CONSOLE_POINTER } from './copy'

/*
 * WHAT THE REST OF THE OWNER'S PAGE SAYS ABOUT PHONES (#431): the console's pointer to where phones
 * pair. The words are pinned verbatim; where they stand is read from the component's source. The
 * Recover card re-issues nothing since recovery by key (#325), so it says nothing about phones.
 */

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8')
const CONSOLE = read('../components/owner/OwnerConsole.svelte')
const withoutComments = (src: string) => src.replace(/<!--[\s\S]*?-->/g, '')

describe("the owner console's pointer", () => {
  it('says where phones pair, in one line, inside the Server connection panel', () => {
    expect(CONSOLE_POINTER).toBe("Phones pair from the Connect to your sync server card on the owner's page.")
    const markup = withoutComments(CONSOLE)
    const panel = markup.slice(markup.indexOf('<details class="conn">'), markup.indexOf('</details>'))
    expect(panel).toContain('<summary>Server connection')
    expect(panel).toContain('{CONSOLE_POINTER}')
    // Positive control: the slice is the panel, not the whole file.
    expect(panel).not.toContain('<SharingStrip')
  })
})
