package com.sufyan.harness.ui.review

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Difference
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.data.DiffEngine
import com.sufyan.harness.runtime.ReviewedChange
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.HarnessColors
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.theme.Radius
import com.sufyan.harness.ui.theme.Spacing

/**
 * §12 / §46 — review what the AI actually changed.
 *
 * Diffs are computed from the real file contents captured before the session started, so this is a
 * record of what happened on disk, not a summary the model wrote about itself. Any file can be put
 * back individually.
 */
@Composable
fun ReviewScreen(vm: HarnessViewModel, onBack: () -> Unit, onOpenFile: (String) -> Unit) {
    val changes by vm.changes.collectAsState()
    var revertAll by remember { mutableStateOf(false) }
    var revertOne by remember { mutableStateOf<ReviewedChange?>(null) }

    LaunchedEffect(Unit) { vm.refreshChanges() }

    val added = changes.sumOf { it.diff.added }
    val removed = changes.sumOf { it.diff.removed }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            "AI changes",
            subtitle = if (changes.isEmpty()) "Nothing changed yet" else "${changes.size} file(s) \u00b7 +$added / -$removed",
            onBack = onBack,
            actions = {
                IconButton(onClick = { vm.refreshChanges() }) { Icon(Icons.Default.Refresh, "Refresh") }
            },
        )

        if (changes.isEmpty()) {
            EmptyState(
                Icons.Outlined.Difference,
                "No changes to review",
                "When the assistant writes to files in this project, every edit shows up here with a diff " +
                    "and a one-tap revert.",
            )
            return@Column
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(changes, key = { it.path }) { change ->
                ChangeCard(
                    change = change,
                    onOpen = { onOpenFile(change.path) },
                    onRevert = { revertOne = change },
                )
            }
            item { Spacer(Modifier.height(Spacing.xl)) }
        }

        Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    Modifier.fillMaxWidth().padding(Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    PrimaryButton("Keep all", { vm.acceptChanges(); onBack() }, Modifier.weight(1f), icon = Icons.Default.Check)
                    SecondaryButton("Revert all", { revertAll = true }, Modifier.weight(1f), icon = Icons.Default.Undo)
                }
            }
        }
    }

    if (revertAll) {
        ConfirmDialog(
            title = "Revert every change?",
            message = "All ${changes.size} file(s) go back to their contents from before this session. " +
                "Files the assistant created are deleted.",
            confirmLabel = "Revert all",
            destructive = true,
            onConfirm = { vm.revertAllChanges(); revertAll = false },
            onDismiss = { revertAll = false },
        )
    }
    revertOne?.let { change ->
        ConfirmDialog(
            title = "Revert ${change.path.substringAfterLast('/')}?",
            message = if (change.isNew) "This file was created during the session and will be deleted."
            else "The file goes back to its contents from before this session.",
            confirmLabel = "Revert",
            destructive = true,
            onConfirm = { vm.revertChange(change); revertOne = null },
            onDismiss = { revertOne = null },
        )
    }
}

@Composable
private fun ChangeCard(change: ReviewedChange, onOpen: () -> Unit, onRevert: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    HarnessCard {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Icon(
                    when {
                        change.isNew -> Icons.Default.NoteAdd
                        change.isDeleted -> Icons.Default.DeleteOutline
                        else -> Icons.Default.EditNote
                    },
                    null,
                    tint = when {
                        change.isNew -> HarnessColors.Ok
                        change.isDeleted -> HarnessColors.Danger
                        else -> HarnessColors.Info
                    },
                    modifier = Modifier.size(18.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(change.path.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(
                        change.path,
                        style = MonoStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Text("+${change.diff.added}", style = MonoStyle.copy(fontSize = 12.sp), color = HarnessColors.Ok)
                Text("-${change.diff.removed}", style = MonoStyle.copy(fontSize = 12.sp), color = HarnessColors.Danger)
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(expanded) {
                Column {
                    DiffBody(change.diff)
                    Row(
                        Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        TextButton(onClick = onOpen) { Text("Open in editor") }
                        TextButton(onClick = onRevert) { Text("Revert", color = HarnessColors.Danger) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffBody(diff: DiffEngine.FileDiff) {
    if (diff.binary) {
        Text(
            "Binary file \u2014 no textual diff.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        )
        return
    }
    Column(
        Modifier
            .padding(horizontal = Spacing.md)
            .clip(RoundedCornerShape(Radius.md))
            .background(HarnessColors.Base)
            .horizontalScroll(rememberScrollState())
            .padding(Spacing.sm),
    ) {
        diff.hunks.take(20).forEach { hunk ->
            Text(hunk.header, style = MonoStyle.copy(fontSize = 11.sp), color = HarnessColors.Info)
            hunk.lines.forEach { line ->
                val (prefix, color) = when (line.kind) {
                    DiffEngine.Kind.Added -> "+" to HarnessColors.Ok
                    DiffEngine.Kind.Removed -> "-" to HarnessColors.Danger
                    DiffEngine.Kind.Context -> " " to HarnessColors.TextMuted
                }
                Text(
                    "$prefix${line.text}",
                    style = MonoStyle.copy(fontSize = 11.sp),
                    color = color,
                    maxLines = 1,
                )
            }
        }
        if (diff.hunks.size > 20) {
            Text(
                "\u2026 ${diff.hunks.size - 20} more hunk(s)",
                style = MonoStyle.copy(fontSize = 11.sp),
                color = HarnessColors.TextMuted,
            )
        }
    }
}
