/*
 * SAS wordlist — a vendored, license-clean list of 256 short, distinct, common English
 * words used ONLY to render a short authentication string (SAS) for out-of-band comparison.
 *
 * This list is original to this project (hand-curated common nouns/adjectives), so there is
 * no upstream license to track — it satisfies the "fully vendored, license-clean, no CDN"
 * invariant. It is a pure constant with no logic and no network access.
 *
 * Exactly 256 entries, so one byte maps to exactly one word (index = byte value). Every word
 * is lowercase ASCII, 3–6 letters, and distinct. The SAS derives words from a BLAKE2b hash of
 * both parties' public keys; it is DISPLAY-ONLY and never a key input — trust is bound by the
 * TOFU pin, not by the phrase.
 */
export const SAS_WORDLIST: readonly string[] = [
  'able', 'acid', 'acorn', 'actor', 'adobe', 'agent', 'album', 'alert',
  'alien', 'alley', 'amber', 'angle', 'ankle', 'apple', 'april', 'apron',
  'arena', 'armor', 'array', 'arrow', 'atlas', 'attic', 'audio', 'award',
  'badge', 'baker', 'banjo', 'basil', 'basin', 'beach', 'beard', 'beast',
  'begin', 'berry', 'bison', 'blade', 'blaze', 'blend', 'block', 'bloom',
  'board', 'bonus', 'boost', 'booth', 'brain', 'brake', 'brave', 'bread',
  'brick', 'brief', 'bring', 'broom', 'brush', 'bugle', 'bunny', 'cabin',
  'cable', 'cacao', 'camel', 'candy', 'canoe', 'canon', 'cargo', 'carol',
  'catch', 'cedar', 'chair', 'chalk', 'charm', 'chart', 'chase', 'cheer',
  'chess', 'chief', 'chime', 'chunk', 'cider', 'cliff', 'climb', 'cloak',
  'clock', 'cloud', 'clove', 'clown', 'coach', 'coast', 'cobra', 'cocoa',
  'comet', 'coral', 'couch', 'crane', 'crate', 'creek', 'crest', 'crisp',
  'crown', 'curve', 'daisy', 'dance', 'delta', 'depth', 'diary', 'diner',
  'ditch', 'diver', 'dodge', 'dough', 'draft', 'drake', 'dream', 'dress',
  'drift', 'drink', 'drone', 'eagle', 'earth', 'easel', 'ebony', 'elbow',
  'elder', 'elite', 'ember', 'emoji', 'empty', 'entry', 'equal', 'exact',
  'fable', 'fairy', 'fancy', 'fault', 'feast', 'fence', 'ferry', 'fever',
  'field', 'fiber', 'final', 'flame', 'flare', 'flash', 'fleet', 'flint',
  'float', 'flock', 'flour', 'flute', 'focus', 'foggy', 'forge', 'found',
  'frost', 'fruit', 'fudge', 'gauge', 'ghost', 'giant', 'glade', 'gleam',
  'glide', 'globe', 'glory', 'glove', 'grace', 'grain', 'grape', 'grasp',
  'grass', 'green', 'grove', 'guard', 'guest', 'guide', 'habit', 'harbor',
  'hasty', 'hatch', 'haven', 'hazel', 'heart', 'hedge', 'hello', 'heron',
  'hinge', 'hobby', 'honey', 'horse', 'hotel', 'house', 'human', 'humor',
  'ideal', 'igloo', 'image', 'index', 'inlet', 'input', 'ivory', 'jelly',
  'jewel', 'joker', 'jolly', 'juice', 'kayak', 'ketch', 'kiosk', 'kite',
  'koala', 'label', 'labor', 'lance', 'large', 'laser', 'latch', 'layer',
  'leader', 'ledge', 'lemon', 'level', 'lever', 'light', 'lilac', 'linen',
  'llama', 'lodge', 'lotus', 'lucky', 'lunar', 'magic', 'mango', 'maple',
  'march', 'marsh', 'medal', 'melon', 'mercy', 'metal', 'meter', 'mimic',
  'miner', 'model', 'money', 'month', 'moral', 'motor', 'mound', 'mouse',
  'nacho', 'nerve', 'niche', 'noble', 'north', 'novel', 'nurse', 'oasis',
]
