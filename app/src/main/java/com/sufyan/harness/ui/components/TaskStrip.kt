package com.sufyan.harness.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sufyan.harness.runtime.RuntimeTask
import com.sufyan.harness.ui.theme.HarnessColors
import com.sufyan.harness.ui.theme.Spacing

/**
 * §13 / §51 — the visible half of the background runtime.
 *
 * The list comes straight from the task registry that also drives the foreground notification, so
 * what is shown here is exactly what is running. Each entry offers the stop action that genuinely
 * applies to it.
 */
@Composable
fun TaskStrip(tasks: List<RuntimeTask>, onStop: (RuntimeTask) -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(tasks.isNotEmpty()) {
        Row(
            modifier
                .fillMaxWidth()
                .background(HarnessColors.Accent.copy(alpha = 0.10f))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            tasks.forEach { task ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when (task.kind) {
                            RuntimeTask.Kind.Server -> Icons.Default.Dns
                            RuntimeTask.Kind.Build -> Icons.Default.Android
                            RuntimeTask.Kind.Agent -> Icons.Default.AutoAwesome
                            RuntimeTask.Kind.Install -> Icons.Default.Download
                            RuntimeTask.Kind.Shell -> Icons.Default.Terminal
                        },
                        contentDescription = null,
                        tint = HarnessColors.Accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Text(task.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    TextButton(onClick = { onStop(task) }, modifier = Modifier.heightIn(min = 28.dp)) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop ${task.label}", modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("Stop", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
