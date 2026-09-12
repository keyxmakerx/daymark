package com.daymark.app.ui.insights

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.stats.YearReview
import com.daymark.app.ui.components.NightBg
import com.daymark.app.ui.components.NightFaint
import com.daymark.app.ui.components.NightInk
import com.daymark.app.ui.components.drawMoodStar
import com.daymark.app.ui.theme.moodColors
import com.daymark.app.ui.theme.moodLabels
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Review my year" — a calm, full-screen, swipe-or-tap walkthrough of a year as a night sky: an
 * intro, a page per quarter with that stretch's stars, then a finale of two plain facts. Every word
 * comes from [YearReview] (rules-based, descriptive, no AI). Tap (or swipe) to move forward; skip
 * anytime.
 *
 * **There is no export.** The finale used to offer "Save keepsake", which wrote the year out as a
 * PNG. See [ReviewYearViewModel] for why that is gone rather than restricted.
 */
@Composable
fun ReviewYearScreen(
    onDone: () -> Unit,
    viewModel: ReviewYearViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val moods = MaterialTheme.moodColors
    val labels = MaterialTheme.moodLabels
    val review = remember(state) {
        YearReview.build(state.year, state.dayMoods, { labels.forLevel(it) })
    }
    val scope = rememberCoroutineScope()

    Surface(color = NightBg, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            StarfieldBackground()

            if (review.totalStars == 0) {
                EmptyReview(review.year, onDone)
                return@Box
            }

            val pageCount = review.chapters.size + 2 // intro + chapters + finale
            val pagerState = rememberPagerState(pageCount = { pageCount })
            fun advance() {
                if (pagerState.currentPage >= pageCount - 1) onDone()
                else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
            }

            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val current = pagerState.currentPage == page
                val appear by animateFloatAsState(if (current) 1f else 0.2f, tween(700), label = "appear")
                // Tap anywhere (outside buttons) to move forward — a gentle, hands-off pace.
                val tapModifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { advance() }
                when (page) {
                    0 -> IntroPage(review, appear, tapModifier) { advance() }
                    // The finale is a place to dwell — a stray tap shouldn't close it; only "Done".
                    pageCount - 1 -> FinalePage(review, appear, Modifier.fillMaxSize(), onDone)
                    else -> ChapterPage(review.chapters[page - 1], moods, appear, page, tapModifier)
                }
            }

            // Honour "skip anytime" with a visible control (the finale has its own Done button).
            if (pagerState.currentPage < pageCount - 1) {
                TextButton(
                    onClick = onDone,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) { Text("Skip", color = NightFaint) }
            }

            ProgressDots(
                count = pageCount,
                current = pagerState.currentPage,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
            )
        }
    }
}

@Composable
private fun IntroPage(review: YearReview.Review, appear: Float, modifier: Modifier, onBegin: () -> Unit) {
    Column(
        modifier = modifier.padding(horizontal = 28.dp).alpha(appear),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label("A LOOK BACK · ${review.year}")
        Spacer16()
        Text(
            "Your year,\none star at a time.",
            style = MaterialTheme.typography.headlineMedium,
            fontStyle = FontStyle.Italic,
            color = NightInk,
            textAlign = TextAlign.Center,
        )
        Spacer16()
        Text(
            "${review.totalStars} days you showed up for yourself. Let’s walk through them — gently, no scores.",
            style = MaterialTheme.typography.bodyMedium,
            color = NightFaint,
            textAlign = TextAlign.Center,
        )
        Spacer16()
        NightButton("Begin", onBegin)
        Spacer(8)
        Text("tap to continue · skip anytime", style = MaterialTheme.typography.labelSmall, color = NightFaint)
    }
}

@Composable
private fun ChapterPage(
    chapter: YearReview.Chapter,
    moods: com.daymark.app.ui.theme.MoodColors,
    appear: Float,
    seed: Int,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 26.dp).alpha(appear),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(chapter.label.uppercase())
        Spacer(6)
        chapter.summary?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = NightInk, textAlign = TextAlign.Center)
        }
        Spacer16()
        StarCluster(chapter.starLevels, moods, seed, modifier = Modifier.size(240.dp))
        // A chapter used to carry a "highlight" chip under the cluster, and it only ever held one
        // of two sentences: "Longest streak began here · 4 days" or "Brightest month · July". Both
        // are the finale's deleted tiles wearing a different shape one page earlier — a run that
        // ended, and a rank of the person's own months against each other. The quarter's own
        // summary above already says what was logged.
    }
}

/**
 * The last page: the year, at most two facts, and the way out.
 *
 * The heading is the year and nothing else. It used to read "{n} stars. This was your year." — a
 * count, and then a sentence telling someone what their year was.
 *
 * Two tiles where there were three, and neither of the survivors ranks anything. **Most often** is
 * the mood the person chose on more days than any other, printed in their own word for it, and it
 * is omitted rather than dashed out when no day that year carries a mood — a "–" in a tile is
 * still a tile, and it invites the reader to wonder what should have been in it. **First entry**
 * is the day they started; it is present whenever the year has anything in it at all.
 *
 * What went, and why, is in [YearReview]'s docstring. There is no "Save keepsake" button; the
 * sentence that stands where it stood says what is true of everything on this screen.
 */
@Composable
private fun FinalePage(
    review: YearReview.Review,
    appear: Float,
    modifier: Modifier,
    onDone: () -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = 28.dp).alpha(appear),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            review.year.toString(),
            style = MaterialTheme.typography.headlineMedium,
            fontStyle = FontStyle.Italic,
            color = NightInk,
            textAlign = TextAlign.Center,
        )
        Spacer16()
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            review.mostOftenMoodLabel?.let {
                Stat(it, "Most often", Modifier.weight(1f))
            }
            review.firstEntryLabel?.let {
                Stat(it, "First entry", Modifier.weight(1f))
            }
        }
        Spacer16()
        Text(
            "This stays on your phone. You can come back to it any time.",
            style = MaterialTheme.typography.bodyMedium,
            color = NightFaint,
            textAlign = TextAlign.Center,
        )
        Spacer16()
        NightButton("Done", onDone)
    }
}

@Composable
private fun StarCluster(
    levels: List<Int>,
    moods: com.daymark.app.ui.theme.MoodColors,
    seed: Int,
    modifier: Modifier = Modifier,
) {
    // Sunflower (golden-angle) layout for an even, organic cluster; deterministic per seed.
    val shown = levels.take(120)
    // Decorative — the chapter's summary text already conveys the meaning to screen readers.
    Canvas(modifier = modifier.clearAndSetSemantics {}) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val spacing = (size.minDimension / 2f) / sqrt(shown.size.coerceAtLeast(1).toFloat() + 1f)
        shown.forEachIndexed { i, level ->
            val angle = (i + seed * 7) * 2.399963f
            val radius = spacing * sqrt(i.toFloat())
            drawMoodStar(cx + cos(angle) * radius, cy + sin(angle) * radius, level, moods.forLevel(level))
        }
    }
}

@Composable
private fun StarfieldBackground() {
    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
        for (i in 0 until 70) {
            // Deterministic faint specks.
            var h = i * 374761393
            h = (h xor (h ushr 13)) * 1274126177
            val x = ((h ushr 8) and 0xFFFF) / 65536f * size.width
            val y = ((h ushr 16) and 0xFFFF) / 65536f * size.height
            val a = 0.10f + ((h ushr 4) and 0x7) / 7f * 0.18f
            drawCircle(NightInk.copy(alpha = a), radius = (1f + (i % 3)) , center = Offset(x, y))
        }
    }
}

/**
 * The empty year. The button stays, and it is not decoration: this screen is full-screen, has no
 * year selector and no top bar, so "Done" is the only visible way back out of it. A dead end with
 * a sentence about having no entries in it is the worst place in the product to strand somebody.
 */
@Composable
private fun EmptyReview(year: Int, onDone: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "No entries for $year.",
            style = MaterialTheme.typography.titleLarge,
            color = NightInk,
            textAlign = TextAlign.Center,
        )
        Spacer16()
        Text(
            "No days logged. Each logged day is drawn here.",
            style = MaterialTheme.typography.bodyMedium,
            color = NightFaint,
            textAlign = TextAlign.Center,
        )
        Spacer16()
        NightButton("Done", onDone)
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = NightInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = NightFaint,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = NightFaint)
}

@Composable
private fun NightButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = NightInk, contentColor = NightBg),
    ) { Text(text) }
}

@Composable
private fun ProgressDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        for (i in 0 until count) {
            val on = i == current
            Box(
                Modifier
                    .height(7.dp)
                    .width(if (on) 18.dp else 7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (on) NightInk else Color(0xFF3A3730)),
            )
        }
    }
}

@Composable private fun Spacer16() = Box(Modifier.height(16.dp))

@Composable private fun Spacer(height: Int) = Box(Modifier.height(height.dp))
