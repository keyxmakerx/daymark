import { describe, it, expect } from 'vitest'
import { MOODS, moodWord, ownMoodColours, ownMoodFill } from './mood'

/*
 * A person's own names and colours for their moods, as their backup carries them (#280).
 *
 * The file is theirs, but it is still a file: hand-edited, from an older phone, or crafted. So
 * every property below is a statement about what comes OUT — a word that is a word, a colour that
 * is `#rrggbb` — and each refusal is paired with the same input carrying one good value, which
 * the reader must see.
 */

const HEX = /^#[0-9a-f]{6}$/

describe('a mood as a word', () => {
  it('is the person’s own name for the level where the backup carries one', () => {
    expect(moodWord(3, { '3': 'Flat' })).toBe('Flat')
    expect(moodWord(3, { '3': '  Flat  ' })).toBe('Flat')
    expect(moodWord(5, { '5': 'Bright', '3': 'Flat' })).toBe('Bright')
  })

  it('is the shipped word where the name is absent, blank or not text', () => {
    expect(MOODS.map((m) => moodWord(m.level, {}))).toEqual(['Awful', 'Bad', 'Meh', 'Good', 'Rad'])
    expect(moodWord(3, undefined)).toBe('Meh')
    expect(moodWord(3, null)).toBe('Meh')
    expect(moodWord(3, 'Flat')).toBe('Meh') // not an object of names
    expect(moodWord(3, { '3': '   ' })).toBe('Meh')
    expect(moodWord(3, { '3': 7 })).toBe('Meh') // a number is not a word
    expect(moodWord(3, { '4': 'Flat' })).toBe('Meh') // another level's name is not this one's
    // Control: the same shapes with a real name in them are read.
    expect(moodWord(3, { '3': 'Flat', '4': 'Up' })).toBe('Flat')
  })
})

describe('a person’s own mood colours', () => {
  it('reads the phone’s ARGB integers, signed or not, as #rrggbb', () => {
    // Color.toArgb() is a signed Int: 0xFFAE5747 arrives as -5351609.
    const signed = ownMoodColours({ '1': -5351609 })
    expect(ownMoodFill(1, signed)).toBe('#ae5747')
    // The same colours written unsigned, as a hand-edited file might.
    const unsigned = ownMoodColours({ '1': 0xffae5747, '5': 0xff5c7c99 })
    expect(ownMoodFill(1, unsigned)).toBe('#ae5747')
    expect(ownMoodFill(5, unsigned)).toBe('#5c7c99')
  })

  it('draws every colour solid, whatever alpha it carries', () => {
    // 0x805E8A66 (half alpha), signed; and 0x00FF8800 (none). A see-through square would read as
    // no square at all.
    const p = ownMoodColours({ '2': 0x805e8a66 - 0x1_0000_0000, '3': 0x00ff8800 })
    expect(ownMoodFill(2, p)).toBe('#5e8a66')
    expect(ownMoodFill(3, p)).toBe('#ff8800')
  })

  it('leaves each level the person did not recolour to the shipped ramp', () => {
    const p = ownMoodColours({ '4': -5351609 })
    expect(ownMoodFill(4, p)).toBe('#ae5747')
    for (const level of [1, 2, 3, 5]) expect(ownMoodFill(level, p)).toBeUndefined()
  })

  it('refuses a value that is not a 32-bit integer, and writes no CSS from the file', () => {
    const hostile: Record<string, unknown> = {
      '1': 'red; background: url(https://example.invalid/x)',
      '2': 1.5,
      '3': Number.NaN,
      '4': Number.POSITIVE_INFINITY,
      '5': 0x1_0000_0000,
    }
    const p = ownMoodColours(hostile)
    for (const level of [1, 2, 3, 4, 5]) expect(ownMoodFill(level, p), `level ${level}`).toBeUndefined()
    // Also refused: the smallest integer below the signed range, and a colour written as text.
    expect(ownMoodFill(1, ownMoodColours({ '1': -0x8000_0001 }))).toBeUndefined()
    expect(ownMoodFill(1, ownMoodColours({ '1': '#ae5747' }))).toBeUndefined()
    // Control: the same object with one real colour in it is read.
    expect(ownMoodFill(1, ownMoodColours({ ...hostile, '1': -5351609 }))).toBe('#ae5747')
  })

  it('reads only the levels one to five', () => {
    const p = ownMoodColours({ '0': -5351609, '6': -5351609, x: -5351609, '1.0': -5351609, ' 1': -5351609 })
    for (const level of [0, 1, 2, 3, 4, 5, 6]) expect(ownMoodFill(level, p)).toBeUndefined()
    // Control: the key "1" is read.
    expect(ownMoodFill(1, ownMoodColours({ '1': -5351609 }))).toBe('#ae5747')
  })

  it('is the whole shipped ramp when the backup carries no colours, or carries them malformed', () => {
    for (const raw of [undefined, null, {}, [], [-5351609], 'colours', 42]) {
      const p = ownMoodColours(raw)
      for (const level of [1, 2, 3, 4, 5]) expect(ownMoodFill(level, p), String(raw)).toBeUndefined()
    }
  })

  it('gives #rrggbb for every integer in 32 bits, edges included', () => {
    // A fixed-seed walk over the range, read unsigned and signed by turns, plus both ends of it
    // and the sign boundary.
    const edges = [-0x8000_0000, -1, 0, 1, 0x7fff_ffff, 0x8000_0000, 0xffff_ffff]
    let seed = 0x2808_0335
    const walk: number[] = []
    for (let i = 0; i < 2_000; i++) {
      seed = (Math.imul(seed, 1_103_515_245) + 12_345) >>> 0
      walk.push(i % 2 === 0 ? seed : seed | 0)
    }
    expect(walk.some((v) => v < 0)).toBe(true) // the signed half really is signed
    let read = 0
    for (const value of [...edges, ...walk]) {
      const fill = ownMoodFill(3, ownMoodColours({ '3': value }))
      expect(fill, String(value)).toMatch(HEX)
      read++
    }
    expect(read).toBe(edges.length + 2_000)
    expect(ownMoodFill(3, ownMoodColours({ '3': 0 }))).toBe('#000000')
    expect(ownMoodFill(3, ownMoodColours({ '3': -1 }))).toBe('#ffffff')
  })

  it('holds nothing a caller can read except through ownMoodFill', () => {
    const p = ownMoodColours({ '1': -5351609 })
    expect(Object.keys(p)).toEqual([])
    expect(JSON.stringify(p)).toBe('{}')
    expect(Object.isFrozen(p)).toBe(true)
    // Two palettes are two palettes: one person's colours never answer for another's.
    const other = ownMoodColours({ '1': 0xff5c7c99 })
    expect(ownMoodFill(1, p)).toBe('#ae5747')
    expect(ownMoodFill(1, other)).toBe('#5c7c99')
  })
})
