<script lang="ts">
  /*
   * Fixed, non-server-supplied assurance banner for the therapist portal. The TOTP path is the
   * HONESTLY WEAKER custody path (COMPANION_SECURITY.md §5.2/§11): the reading key is unwrapped in
   * this browser under a passphrase, and the page is served by the server it talks to — so a
   * tampered page could capture the passphrase or keys. We do NOT claim CSP/SRI make this
   * zero-knowledge. Keys stay in memory only and are wiped on logout/idle.
   *
   * TOKENS. This banner used to be washed in --mood-3 / --mood-3-wash with a mood-3 dot: the
   * middle of the DATA ramp, borrowed to mean "middling assurance". The ramp encodes a person's
   * reported experience and nothing else, and a custody caveat is not that person's experience.
   * Unlike the non-diagnostic framing next to it, this one IS a genuine warning — a real,
   * standing limitation of this sign-in path — so it takes warn severity (--amber) via
   * ui/Callout, which is the substrate the fixed honesty banners are supposed to sit on.
   *
   * The decorative dot is gone rather than recoloured. Callout carries the severity in three
   * non-colour ways the dot never did: a broken (dashed) left rule that survives greyscale and
   * a printout, a visually hidden "Warning:" prefix for screen readers, and the words — which
   * were always the point. The <aside> stays so the region keeps its accessible name.
   *
   * The copy below is fixed and quoted verbatim in the security notes. Restyle the container;
   * never the sentences.
   */
  import { Callout } from '../ui'
</script>

<aside aria-label="Assurance notice">
  <Callout tone="warn">
  <!-- The five lines below are byte-for-byte the fixed copy, indentation included. They are NOT
       re-indented to match their new nesting: keeping the bytes identical is what makes "did
       anyone touch the wording?" a trivial diff for the next reviewer. -->
  <p>
    <strong>Lower-assurance path (TOTP).</strong> Your reading key is unwrapped in this browser under
    a passphrase. Because the page is served by the server it talks to, a tampered page could capture
    your passphrase or keys — this is a convenience path, not a zero-knowledge guarantee. Keys are held
    in memory only and wiped when you log out or go idle. Verify the released image digest; a hardware
    passkey (WebAuthn) is the stronger path when available.
  </p>
  </Callout>
</aside>

<style>
  p {
    margin: 0;
  }
</style>
