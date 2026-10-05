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

    const val COLOUR = "Colour is age. New memories are blue-white; older ones turn white, gold, " +
        "then red, and drift outward. Red only means old, never bad."

    fun entries(layout: SkyLayout, constellations: Int): List<Entry> {
        if (layout.starCount == 0) return emptyList()
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
        val shapes = layout.shape.toSet()
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
        if ((0 until layout.starCount).any { layout.kindAt(it) == SkyKind.LIFE_EVENT }) {
            out.add(
                Entry(
                    "Life events",
                    "Marks you placed yourself. Up close they carry a ring and four rays, and zoomed " +
                        "right in they burn as bigger suns.",
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
