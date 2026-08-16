package com.daymark.app.ui.goals

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daymark.app.R
import com.daymark.app.goals.GoalKind
import com.daymark.app.goals.GoalReached
import com.daymark.app.ui.components.PaperSurface
import com.daymark.app.ui.theme.HairlineWidth
import com.daymark.app.ui.theme.moodColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(
    onGoalClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GoalsViewModel = hiltViewModel(),
) {
    val goals by viewModel.goals.collectAsStateWithLifecycle()

    if (goals.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("No goals yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tap + for a weekly habit, or a project with steps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(goals, key = { it.goal.id }) { ui ->
                GoalCard(ui, onClick = { onGoalClick(ui.goal.id) }, modifier = Modifier.animateItem())
            }
        }
    }
}

@Composable
private fun GoalCard(ui: GoalProgressUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PaperSurface(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .border(HairlineWidth, MaterialTheme.colorScheme.outline, RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_ui_target),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(ui.goal.title, style = MaterialTheme.typography.titleMedium)
                if (ui.reached) {
                    // Instead of the progress line, not beside it. Once someone has said a goal is
                    // reached, "0 of 3 done" with a near-empty bar under it is a running report on
                    // how little they did this week towards a thing they have finished with — which
                    // is the shape docs/DECISIONS_2026-08.md §D6 rules out, arrived at sideways.
                    // Neither number is lost: the target and the board are both still in the editor,
                    // one tap away, and unmarking brings the line straight back.
                    //
                    // Same type, same colour, same place as the line it replaces. A reached goal is
                    // not styled as a prize — see GoalReached on why the copy names the act instead
                    // of praising it.
                    Text(
                        GoalReached.REACHED_NOTE,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (ui.kind == GoalKind.PROJECT) {
                    // A project gets the sentence and no bar. A bar is a fraction, and
                    // docs/DECISIONS_2026-08.md §D6 rules out percentage rings and burndown on this
                    // surface — "3 of 7 steps done" is the whole of what a project claims about
                    // itself. See GoalBoard.Progress.
                    Text(
                        ui.project.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "${ui.goal.targetPerWeek}× per week · ${ui.completed} of ${ui.target} done",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ProgressBar(fraction = ui.fraction, met = ui.isMet)
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float, met: Boolean) {
    val fill = if (met) MaterialTheme.moodColors.good else MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(fill),
        )
    }
}
