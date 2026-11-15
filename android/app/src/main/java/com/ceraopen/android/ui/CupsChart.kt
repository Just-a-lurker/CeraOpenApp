// SPDX-License-Identifier: GPL-3.0-or-later
// The cup history chart: bars, three faint gridlines, no legend, no chart
// library. One hue on the data; a selected bar turns to ink.
package com.ceraopen.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ceraopen.android.PeriodBar

private val PLOT_HEIGHT = 148.dp

@Composable
fun CupsBars(
    bars: List<PeriodBar>,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bars.isEmpty()) return
    val peak = (bars.maxOfOrNull { it.cups ?: 0 } ?: 0).coerceAtLeast(1)
    // A three-line scale only makes sense when the midpoint is a whole cup,
    // so an odd peak is rounded up to the next even number.
    val max = if (peak % 2 == 0) peak else peak + 1
    // Twelve bars is the most that can carry a label on a phone.
    val dense = bars.size > 12
    val grid = MaterialTheme.colorScheme.outlineVariant
    val baseline = MaterialTheme.colorScheme.outline

    Row(modifier.fillMaxWidth()) {
        // A scale is never optional: three ticks, even steps, zero at the base.
        Column(
            modifier = Modifier.width(20.dp).height(PLOT_HEIGHT),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            ScaleTick(max.toString())
            ScaleTick((max / 2).toString())
            ScaleTick("0")
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().height(PLOT_HEIGHT)) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    for (f in listOf(0f, 0.5f, 1f)) {
                        drawLine(
                            color = grid,
                            start = Offset(0f, h * f),
                            end = Offset(w, h * f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    drawLine(
                        color = baseline,
                        start = Offset(0f, h - 0.5f),
                        end = Offset(w, h - 0.5f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
                    bars.forEachIndexed { index, bar ->
                        BarCell(
                            bar = bar,
                            max = max,
                            dense = dense,
                            selected = selected == index,
                            dimmed = selected != null && selected != index,
                            onClick = { onSelect(if (selected == index) null else index) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                bars.forEachIndexed { index, bar ->
                    Text(
                        text = if (showLabel(index, bars.size, dense)) bar.label else "",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                        // A two-digit day is wider than its 1/30th cell; let it
                        // spill into the empty cells either side instead of
                        // clipping to "1".
                        overflow = TextOverflow.Visible,
                    )
                }
            }
        }
    }
}

private fun showLabel(index: Int, count: Int, dense: Boolean): Boolean =
    if (!dense) true else index == 0 || index == count - 1 || (index + 1) % 5 == 0

@Composable
private fun ScaleTick(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun RowScope.BarCell(
    bar: PeriodBar,
    max: Int,
    dense: Boolean,
    selected: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val cups = bar.cups
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics {
                contentDescription = if (cups == null) {
                    "${bar.name}: no data yet"
                } else {
                    "${bar.name}: $cups cups"
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        when {
            // A day with no data yet draws nothing: a row of stubs along the
            // axis reads as a dashed baseline, not as "the month continues".
            cups == null -> Unit
            cups == 0 -> Box(
                Modifier
                    .fillMaxWidth(if (dense) 0.7f else 0.55f)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(1.dp)),
            )
            else -> Box(
                Modifier
                    .fillMaxWidth(if (dense) 0.7f else 0.55f)
                    .fillMaxHeight((cups.toFloat() / max).coerceIn(0.05f, 1f))
                    .background(
                        color = when {
                            selected -> ink
                            dimmed -> accent.copy(alpha = 0.32f)
                            else -> accent
                        },
                        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                    ),
            )
        }
    }
}
