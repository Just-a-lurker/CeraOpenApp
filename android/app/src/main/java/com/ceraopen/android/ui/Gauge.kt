// SPDX-License-Identifier: GPL-3.0-or-later
// The temperature dial: a gauge, not a progress bar. It reads like the
// machine's own face, and it is the one large thing on the dashboard.
package com.ceraopen.android.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ceraopen.android.ui.theme.Tabular
import kotlin.math.cos
import kotlin.math.sin

private const val START_ANGLE = 135f
private const val SWEEP = 270f
private const val MIN_C = 20f
private const val MAX_C = 100f

@Composable
fun TemperatureGauge(
    tempC: Int?,
    targetC: Double?,
    modifier: Modifier = Modifier,
) {
    val fraction = tempC?.let { ((it - MIN_C) / (MAX_C - MIN_C)).coerceIn(0f, 1f) } ?: 0f
    val sweep by animateFloatAsState(
        targetValue = fraction * SWEEP,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "gaugeSweep",
    )

    val track = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    val tick = MaterialTheme.colorScheme.outline
    val marker = MaterialTheme.colorScheme.onSurface

    val spoken = tempC?.let { "$it degrees Celsius" } ?: "temperature unknown"
    val target = targetC?.let { "target ${Math.round(it)} degrees" } ?: "no target set"

    Box(
        modifier = modifier.semantics { contentDescription = "Water temperature $spoken, $target" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val radius = (size.minDimension - stroke) / 2f - 20.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)
            val arcTopLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            // Nine ticks from 20 to 100 °C, every 10 degrees.
            for (i in 0..8) {
                val angle = Math.toRadians((START_ANGLE + SWEEP * i / 8f).toDouble())
                val long = i % 4 == 0
                val inner = radius + 11.dp.toPx()
                val outer = radius + (if (long) 19.dp.toPx() else 15.dp.toPx())
                drawLine(
                    color = tick,
                    start = Offset(
                        center.x + inner * cos(angle).toFloat(),
                        center.y + inner * sin(angle).toFloat(),
                    ),
                    end = Offset(
                        center.x + outer * cos(angle).toFloat(),
                        center.y + outer * sin(angle).toFloat(),
                    ),
                    strokeWidth = if (long) 2.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // The empty part of the scale is a hairline, not a second arc: a
            // fat track competes with the data in the dark scheme, and its
            // round cap reads as a stray pill next to the target.
            drawArc(
                color = track,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Butt),
            )
            if (sweep > 0.5f) {
                drawArc(
                    color = accent,
                    startAngle = START_ANGLE,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }

            // The target is a dot sitting on the hairline: a setpoint mark, the
            // idiom every dial uses. It stays on the scale instead of floating
            // outside it, and it does not compete with the degree ticks.
            targetC?.let { t ->
                val f = ((t.toFloat() - MIN_C) / (MAX_C - MIN_C)).coerceIn(0f, 1f)
                val angle = Math.toRadians((START_ANGLE + SWEEP * f).toDouble())
                drawCircle(
                    color = marker,
                    radius = 3.5.dp.toPx(),
                    center = Offset(
                        center.x + radius * cos(angle).toFloat(),
                        center.y + radius * sin(angle).toFloat(),
                    ),
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = tempC?.toString() ?: "–",
                    style = MaterialTheme.typography.displayLarge.merge(Tabular),
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "°C",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
                )
            }
            Text(
                text = targetC?.let { "Target ${Math.round(it)} °C" } ?: "No target",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
