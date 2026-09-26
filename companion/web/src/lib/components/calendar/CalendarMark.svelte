<script lang="ts">
  /*
   * A CALENDAR MARK — one of the four kinds of record, as geometry, in ink.
   *
   * Both calendars draw their kinds with this: the clinician's (therapist/CalendarScreen.svelte)
   * and the person's own month (MonthCalendar.svelte). A mark is 3–11 px. Hue at that size fails
   * for a large minority of readers, fails on a projector, fails in print and fails in a greyscale
   * screenshot. So the four kinds are told apart by SHAPE — filled square, hollow ring, horizontal
   * bar, diagonal slash — and every mark is drawn in `currentColor`, inheriting the ink of what it
   * sits in. Colour carries nothing here, so nothing is lost when colour is. The legends name each
   * shape in words for the same reason.
   *
   * The self-check mark is a slash, not a check mark: a tick on a mental-health surface reads as
   * "done, and well done", which is the software grading someone.
   *
   * A mood is never drawn here. A check-in's square in its mood's colour, beside its word, is
   * MoodMark.svelte; this square is the kind's own, in ink, as the legends and the clinician's
   * calendar draw it.
   */
  import type { EventShape } from '../../calendar/core'

  let { shape }: { shape: EventShape } = $props()
</script>

<span class="mark" data-shape={shape} aria-hidden="true"></span>

<style>
  /* Four geometries, one ink. `currentColor` is deliberate: a mark takes the colour of whatever it
     sits in, so an out-of-month cell's marks fade with their cell and nothing has to be
     re-themed. */
  .mark {
    display: inline-block;
    flex: none;
    color: inherit;
  }

  .mark[data-shape='square'] {
    width: 7px;
    height: 7px;
    background: currentColor;
  }

  .mark[data-shape='ring'] {
    width: 8px;
    height: 8px;
    border: 1.5px solid currentColor;
    border-radius: 50%;
    background: transparent;
  }

  .mark[data-shape='bar'] {
    width: 11px;
    height: 3px;
    background: currentColor;
    border-radius: 1px;
  }

  /* The slash is a rotated stroke inside a box the same size as the ring, so all four marks sit
     on one baseline grid however they are combined. */
  .mark[data-shape='slash'] {
    position: relative;
    width: 8px;
    height: 8px;
  }

  .mark[data-shape='slash']::before {
    content: '';
    position: absolute;
    left: 50%;
    top: -1px;
    width: 2px;
    height: 10px;
    margin-left: -1px;
    background: currentColor;
    transform: rotate(45deg);
  }

  /* Three of the four shapes are backgrounds, which a browser leaves off the paper by default. On
     paper they would vanish and take their kind with them, so they print as drawn. */
  @media print {
    .mark,
    .mark::before {
      -webkit-print-color-adjust: exact;
      print-color-adjust: exact;
    }
  }
</style>
