// SPDX-License-Identifier: GPL-3.0-or-later
// Small shared pieces: the panel that holds a block of the dashboard, the
// segmented control, steppers, the battery, and the app's mark.
package com.ceraopen.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ceraopen.android.ui.theme.LocalCeraDark
import com.ceraopen.android.ui.theme.Tabular
import kotlin.math.roundToInt

/** A block of the dashboard: white on porcelain, one hairline, one radius. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), content = content)
    }
}

@Composable
fun PanelHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** A quiet one-pixel rule between rows inside a panel. */
@Composable
fun RowRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * A track with a raised selected cell: the app's one segmented control, used
 * for brew mode and for the chart period.
 */
@Composable
fun SegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    val dark = LocalCeraDark.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Surface(
                    onClick = { onSelect(index) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.small,
                    // The selected cell sits one step above the track. In the
                    // dark scheme "surface" is darker than the track, so a
                    // white cell there would read as a hole, not a selection.
                    color = when {
                        !selected -> Color.Transparent
                        dark -> MaterialTheme.colorScheme.surfaceContainerHighest
                        else -> MaterialTheme.colorScheme.surface
                    },
                    shadowElevation = if (selected && !dark) 1.dp else 0.dp,
                ) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            selected -> MaterialTheme.colorScheme.onSurface
                            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * A slider with a thin machined track and a round knob. Material's default
 * thumb in this version is a tall bar, which reads as a scrollbar handle.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ValueSlider(
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    description: String,
    enabled: Boolean = true,
) {
    val span = (range.last - range.first).coerceAtLeast(1).toFloat()
    val fraction = ((value - range.first) / span).coerceIn(0f, 1f)
    val accent = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest

    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = 0,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = description },
        thumb = {
            Box(Modifier.size(20.dp).background(accent, CircleShape))
        },
        track = {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .padding(horizontal = 10.dp),
            ) {
                val r = size.height / 2f
                drawRoundRect(color = trackColor, cornerRadius = CornerRadius(r, r))
                if (fraction > 0f) {
                    drawRoundRect(
                        color = accent,
                        size = Size(size.width * fraction, size.height),
                        cornerRadius = CornerRadius(r, r),
                    )
                }
            }
        },
    )
}

/** Minus / value / plus, on a 44 dp target. */
@Composable
fun Stepper(
    display: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    description: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(Icons.Rounded.Remove, "$description, decrease", canDecrease && enabled, onDecrease)
        Text(
            text = display,
            modifier = Modifier.width(76.dp),
            style = MaterialTheme.typography.titleMedium.merge(Tabular),
            textAlign = TextAlign.Center,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
        StepButton(Icons.Rounded.Add, "$description, increase", canIncrease && enabled, onIncrease)
    }
}

@Composable
private fun StepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(
                1.dp,
                if (enabled) {
                    MaterialTheme.colorScheme.outline
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
            ),
        ) {
            Icon(
                icon,
                contentDescription = label,
                modifier = Modifier.padding(9.dp).size(18.dp),
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                },
            )
        }
    }
}

/** Four cells and a percentage, the way the machine's own app shows it. */
@Composable
fun BatteryIndicator(raw: Int?, modifier: Modifier = Modifier) {
    val filled = raw?.let { (it / 25).coerceIn(0, 4) } ?: 0
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics {
                contentDescription = raw?.let { "Battery $it percent" } ?: "Battery level unknown"
            },
        ) {
            repeat(4) { index ->
                Box(
                    Modifier
                        .size(width = 5.dp, height = 11.dp)
                        .background(
                            if (index < filled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            RoundedCornerShape(1.dp),
                        ),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            raw?.let { "$it%" } ?: "–",
            style = MaterialTheme.typography.labelMedium.merge(Tabular),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The small grey line under a title: data source, period, or a hint. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Monospace, for the Bluetooth frame log only. */
@Composable
fun MonoLine(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = color,
        maxLines = 1,
    )
}
