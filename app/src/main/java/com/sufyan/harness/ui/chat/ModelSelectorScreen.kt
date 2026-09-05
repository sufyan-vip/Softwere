package com.sufyan.harness.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.ai.ModelInfo
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.Radius
import com.sufyan.harness.ui.theme.Spacing

private enum class Category(val label: String, val emoji: String) {
    Recent("Recent", "⭐"),
    Fast("Fast", "⚡"),
    Coding("Coding", "💻"),
    Reasoning("Reasoning", "🧠"),
    LowCost("Low Cost", "💰"),
    Premium("Premium", "👑"),
    All("All", "•"),
}

@Composable
fun ModelSelectorScreen(vm: HarnessViewModel, onBack: () -> Unit) {
    val models by vm.models.collectAsState()
    val loading by vm.modelsLoading.collectAsState()
    val error by vm.modelsError.collectAsState()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(Category.All) }
    val current = vm.active.collectAsState().value?.modelId ?: vm.settings.modelId
    val recents = remember { vm.settings.recentModels() }

    LaunchedEffect(Unit) { if (models.isEmpty()) vm.loadModels() }

    val filtered = remember(models, query, category) {
        models.filter { m ->
            (query.isBlank() || m.id.contains(query, true) || m.name.contains(query, true)) &&
                when (category) {
                    Category.All -> true
                    Category.Recent -> m.id in recents
                    Category.Fast -> listOf("mini", "flash", "haiku", "small", "lite", "8b", "7b").any { m.id.contains(it, true) }
                    Category.Coding -> listOf("cod", "deepseek", "qwen", "devstral", "sonnet", "gpt-4", "grok").any { m.id.contains(it, true) }
                    Category.Reasoning -> listOf("o1", "o3", "o4", "r1", "reason", "think", "opus").any { m.id.contains(it, true) }
                    Category.LowCost -> m.isFree || (m.promptPrice ?: 1.0) < 0.0000005
                    Category.Premium -> (m.promptPrice ?: 0.0) > 0.000005
                }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            "AI Model",
            subtitle = if (models.isEmpty()) "OpenRouter" else "${models.size} models on OpenRouter",
            onBack = onBack,
            actions = {
                IconButton(onClick = { vm.loadModels() }) { Icon(Icons.Default.Refresh, contentDescription = "Reload models") }
            },
        )

        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search models...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(Radius.md),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Category.entries.forEach { c ->
                    FilterChip(
                        selected = category == c,
                        onClick = { category = c },
                        label = { Text("${c.emoji} ${c.label}", style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(Radius.pill),
                    )
                }
            }
        }

        when {
            loading && models.isEmpty() -> LoadingState("Loading models from OpenRouter...")
            error != null && models.isEmpty() -> Box(Modifier.padding(Spacing.lg)) {
                ErrorState("Could not load models", error!!, onRetry = { vm.loadModels() })
            }
            filtered.isEmpty() -> EmptyState(Icons.Default.SearchOff, "No models match", "Try a different search or category.")
            else -> LazyColumn(
                contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                items(filtered, key = { it.id }) { m ->
                    ModelRow(m, selected = m.id == current) { vm.selectModel(m.id); onBack() }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: ModelInfo, selected: Boolean, onClick: () -> Unit) {
    HarnessCard(onClick = onClick) {
        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(model.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(model.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Spacer(Modifier.height(Spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    model.contextLength?.let { StatusChip("${it / 1000}K ctx") }
                    if (model.isFree) StatusChip("Free", StatusKind.Ok)
                }
            }
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}
