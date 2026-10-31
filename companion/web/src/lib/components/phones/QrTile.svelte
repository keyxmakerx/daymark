<script lang="ts">
  /*
   * One QR code on a scannable tile, for a link that has to cross from one device to another.
   *
   * The drawing is phones/qr.ts, the vendored encoder, so no runtime package runs beside the
   * owner's token. The tile is --scan-paper and the modules --scan-ink in both themes, because a
   * camera reads dark on light and a night-theme inversion would not scan everywhere.
   *
   * Hidden from assistive technology: every caller prints the same text beside it, which is the
   * text alternative and the way to type it in when a camera is not to hand.
   */
  import { qrDrawing } from '../../phones/qr'

  let { text, size = '14rem' }: { text: string; size?: string } = $props()

  const qr = $derived(qrDrawing(text))
</script>

<div class="qr-tile">
  <svg class="qr" style:width={size} style:height={size} viewBox="0 0 {qr.extent} {qr.extent}" aria-hidden="true" focusable="false" shape-rendering="crispEdges">
    <rect class="qr-paper" width={qr.extent} height={qr.extent} />
    <path class="qr-ink" d={qr.path} />
  </svg>
</div>

<style>
  /* The quiet zone a reader needs is inside the drawing, so the tile has no padding. */
  .qr-tile { align-self: flex-start; width: fit-content; max-width: 100%; border: 1px solid var(--border-strong); border-radius: var(--radius-sm); background: var(--scan-paper); line-height: 0; }
  .qr { display: block; max-width: 100%; }
  .qr-paper { fill: var(--scan-paper); }
  .qr-ink { fill: var(--scan-ink); }
</style>
