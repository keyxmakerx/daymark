package com.daymark.app.sky

/**
 * The Key: what the things in this sky are (`DECISIONS.md` §D11).
 *
 * It leads with colour, because the first thing anyone wonders about a red star is whether red is
 * bad, and the answer is in its first sentence: red only means old. It lists only what is in this
 * person's sky, so it never describes something they do not have and never reads as a list of
 * things to collect. No entry says how a shape is earned or kept; a band is a description of a
 * stretch of dates, never a reward for it.
 *
 * Fixed, human-written lines only. Import-free, like the rest of `sky/`.
 */
object SkyKey {

    data class Entry(val title: String, val text: String)

    /** What a supernova is, here and on its card: a marker the person placed, and nothing more. */
    const val SUPERNOVA = "A life event you marked as hard. It marks that day and nothing more: what " +
        "came after stays where it is."

    const val COLOUR = "Colour is age. New memories are blue-white; older ones turn white, gold, " +
        "then red, and drift outward. Red only means old, never bad."

    fun entries(layout: SkyLayout, constellations: Int, nebulae: Int = 0, trackers: Int = 0): List<Entry> {
        if (layout.shownCount == 0) return emptyList()
        val out = ArrayList<Entry>()
        out.add(Entry("Colour", COLOUR))
        when (layout.form) {
            SkyForm.Form.RIVER -> out.add(
                Entry(
                    "River",
                    "Your memories run along a river, oldest at the top. Every sky takes a shape " +
                        "of its own, and yours is a river.",
                ),
            )
            SkyForm.Form.GALAXIES -> out.add(
                Entry(
                    "Galaxies",
                    "Your memories fill one galaxy, then the next, oldest at the top. Every sky " +
                        "takes a shape of its own, and yours is galaxies.",
                ),
            )
            SkyForm.Form.OPEN -> out.add(
                Entry(
                    "Open sky",
                    "Your memories spread across an open sky, oldest at the top. Every sky takes " +
                        "a shape of its own, and yours has no line to follow.",
                ),
            )
            null -> Unit
        }
        // Only what is in sight: a put-away memory is not described, even by the shape it was in.
        val shapes = (0 until layout.starCount).filter { layout.isShown(it) }.map { layout.shape[it] }.toSet()
        if (SkyForm.SHAPE_BAND in shapes) {
            out.add(
                Entry(
                    "Bands",
                    "Long stretches with something on most days. Memories line up along them and " +
                        "gather into knots. Rare: most skies have one or two.",
                ),
            )
        }
        if (SkyForm.SHAPE_CLUSTER in shapes) {
            out.add(
                Entry(
                    "Clusters",
                    "On-and-off weeks. Each run of days gathers into its own cluster: oval, spiral or open.",
                ),
            )
        }
        if (SkyForm.SHAPE_STREAM in shapes) {
            out.add(Entry("Streams", "A longer run of days, drawn out into a thin trail like a comet."))
        }
        val lifeEvent = (0 until layout.starCount).any {
            layout.isShown(it) && !layout.hard[it] && layout.kindAt(it) == SkyKind.LIFE_EVENT
        }
        if (lifeEvent) {
            out.add(
                Entry(
                    "Life events",
                    "Marks you placed yourself. Up close they carry a ring and four rays, and zoomed " +
                        "right in they burn as bigger suns.",
                ),
            )
        }
        if ((0 until layout.starCount).any { layout.isSupernova(it) }) {
            out.add(Entry("Supernova", SUPERNOVA))
        }
        if (nebulae > 0) {
            out.add(
                Entry(
                    "Nebulae",
                    "Gas around weeks with a lot of writing, in your sky's own colours. A week without " +
                        "one is an ordinary week.",
                ),
            )
        }
        if (trackers > 0) {
            out.add(
                Entry(
                    "Trackers",
                    "Trackers you chose to show, each beside the day it was started. Each time you log " +
                        "one is a star in it, so more logs make it denser, never brighter. Every tracker " +
                        "has its own Show in my sky switch.",
                ),
            )
        }
        if (constellations > 0) {
            out.add(
                Entry(
                    "Constellations",
                    "You draw them between your own stars and name them. Over the years their stars " +
                        "drift apart and they fall out of the sky; each one is kept as a photo of the " +
                        "day you drew it.",
                ),
            )
        }
        return out
    }
}
