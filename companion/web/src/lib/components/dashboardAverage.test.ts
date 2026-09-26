import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

/*
 * A PERSON'S OWN VIEW CARRIES NO AVERAGE MOOD (#361, decided in #203).
 *
 * A person's own views describe what was logged and never mark it, and a mean of somebody's moods
 * is a mark: one figure for how they have been. The Dashboard serves the person (the owner console
 * and the backup viewer) and the clinician reading a share, so the difference is a prop,
 * `showAverage`, off unless a mount asks. Only the clinician's view asks, and there the figure is
 * named as the PDF report names it: an average of what was logged.
 *
 * Structural, because these tests have no DOM: the markup draws the average only behind the prop,
 * and the one file in the tree that passes the prop is the clinician's view.
 */

/** This file is at src/lib/components/, so two levels up is src/. */
const SRC = fileURLToPath(new URL('../../', import.meta.url))
const rel = (abs: string) => 'src/' + abs.slice(SRC.length)

function svelteFiles(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const path = dir + entry.name
    if (entry.isDirectory()) out.push(...svelteFiles(path + '/'))
    else if (entry.name.endsWith('.svelte')) out.push(path)
  }
  return out.sort()
}

const TREE = new Map(svelteFiles(SRC).map((f) => [rel(f), readFileSync(f, 'utf8')]))
const read = (path: string) => {
  const src = TREE.get(path)
  if (src === undefined) throw new Error(`${path} is not in the tree`)
  return src
}

const DASHBOARD = 'src/lib/components/Dashboard.svelte'
const CLINICIAN_VIEW = 'src/lib/components/therapist/SharedDataView.svelte'
const OWN_DATA_VIEWS = ['src/lib/components/owner/OwnerConsole.svelte', 'src/App.svelte']

/** What renders: the markup, with the script, the style and comments removed. */
const markupOf = (src: string) =>
  src
    .replace(/<script[\s\S]*?<\/script>/g, '')
    .replace(/<style[\s\S]*?<\/style>/g, '')
    .replace(/<!--[\s\S]*?-->/g, '')

/** Every `<Dashboard …>` tag a file renders. */
const dashboardTags = (src: string) => markupOf(src).match(/<Dashboard\b[^>]*>/g) ?? []

/** Whether a tag asks for the average. `showAverage={false}` does not. */
const asksForAverage = (tag: string) => /\bshowAverage\b(?!\s*=\s*\{\s*false\s*\})/.test(tag)

/** The files whose Dashboard asks for the average. */
const askers = (tree: Map<string, string>) => [...tree].filter(([, src]) => dashboardTags(src).some(asksForAverage)).map(([p]) => p)

/** A file with its first Dashboard tag asking for the average: the plant for the controls. */
const ask = (src: string) => src.replace(/<Dashboard\b([^>]*?)\s*\/>/, '<Dashboard$1 showAverage />')

/** The markup split into the `{showAverage && …}` expressions and everything outside them. */
function splitOnTheProp(markup: string): { inside: string[]; outside: string } {
  const inside: string[] = []
  let outside = ''
  let from = 0
  for (;;) {
    const at = markup.indexOf('{showAverage &&', from)
    if (at < 0) return { inside, outside: outside + markup.slice(from) }
    outside += markup.slice(from, at)
    let depth = 0
    let end = at
    for (; end < markup.length; end++) {
      if (markup[end] === '{') depth++
      else if (markup[end] === '}' && --depth === 0) break
    }
    inside.push(markup.slice(at, end + 1))
    from = end + 1
  }
}

/** The average is drawn only behind the prop, and each time it is named as an average of what was logged. */
function averageOnlyBehindTheProp(src: string): boolean {
  const { inside, outside } = splitOnTheProp(markupOf(src))
  return (
    inside.length > 0 &&
    inside.every((e) => e.includes('s.averageMood') && e.includes('average of what was logged')) &&
    !/averageMood|\bavg\b/.test(outside)
  )
}

describe('the Dashboard draws an average only when its mount asks', () => {
  const dashboard = read(DASHBOARD)

  it('is off unless asked for', () => {
    expect(dashboard).toMatch(/let \{ data, showAverage = false \}: \{ data: BackupData; showAverage\?: boolean \} = \$props\(\)/)
  })

  it('draws it only behind the prop, named as an average of what was logged', () => {
    expect(averageOnlyBehindTheProp(dashboard)).toBe(true)
  })

  it('fails for an average drawn outside the prop, an unnamed one, or none behind it (positive control)', () => {
    const plants = [
      // The line as it was: "avg 3.42" for everyone.
      dashboard.replace('<span class="sum faint">', "<span class=\"sum faint\">avg {s.averageMood?.toFixed(2) ?? '—'}"),
      dashboard.replace('average of what was logged: ', 'avg '),
      dashboard.replace('{showAverage && s.averageMood !== null', '{s.averageMood !== null'),
    ]
    for (const planted of plants) {
      expect(planted).not.toBe(dashboard)
      expect(averageOnlyBehindTheProp(planted)).toBe(false)
    }
  })
})

describe('only the clinician’s view of a share asks for it', () => {
  it('the clinician’s view asks', () => {
    const tags = dashboardTags(read(CLINICIAN_VIEW))
    expect(tags.length).toBeGreaterThan(0)
    expect(tags.every(asksForAverage)).toBe(true)
  })

  it('no view of a person’s own data asks, and nothing else in the tree does', () => {
    for (const path of OWN_DATA_VIEWS) {
      expect(dashboardTags(read(path)).length, `${path} mounts no Dashboard`).toBeGreaterThan(0)
    }
    expect(askers(TREE)).toEqual([CLINICIAN_VIEW])
  })

  it('the detector sees a view of a person’s own data that asks (positive control)', () => {
    for (const path of OWN_DATA_VIEWS) {
      const planted = ask(read(path))
      expect(planted).not.toBe(read(path))
      expect(askers(new Map([...TREE, [path, planted]]))).toContain(path)
    }
    expect(asksForAverage('<Dashboard {data} showAverage={true} />')).toBe(true)
    expect(asksForAverage('<Dashboard {data} showAverage={false} />')).toBe(false)
    expect(asksForAverage('<Dashboard {data} />')).toBe(false)
  })
})
