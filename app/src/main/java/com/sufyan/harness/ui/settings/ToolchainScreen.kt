package com.sufyan.harness.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sufyan.harness.HarnessViewModel
import com.sufyan.harness.runtime.RuntimeState
import com.sufyan.harness.ui.components.*
import com.sufyan.harness.ui.theme.MonoStyle
import com.sufyan.harness.ui.theme.Spacing

@Composable
fun ToolchainScreen(vm: HarnessViewModel, onBack: () -> Unit) {
    val statuses by vm.toolStatuses.collectAsState()
    val scanning by vm.toolsScanning.collectAsState()
    val runtime by vm.linux.status.collectAsState()

    LaunchedEffect(Unit) {
        vm.refreshRuntime()
        if (statuses.isEmpty()) vm.scanToolchains()
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            "Toolchains",
            subtitle = "Verified by running each tool",
            onBack = onBack,
            actions = {
                IconButton(onClick = { vm.scanToolchains(); vm.refreshRuntime() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                }
            },
        )

        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            HarnessCard {
                Column(Modifier.padding(Spacing.lg)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Linux runtime", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.weight(1f))
                        StatusChip(
                            runtime.state.name,
                            when (runtime.state) {
                                RuntimeState.Installed -> StatusKind.Ok
                                RuntimeState.Failed -> StatusKind.Error
                                RuntimeState.Downloading, RuntimeState.Extracting -> StatusKind.Info
                                RuntimeState.NotInstalled -> StatusKind.Neutral
                            },
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    Text(runtime.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (runtime.state == RuntimeState.Downloading || runtime.state == RuntimeState.Extracting) {
                        Spacer(Modifier.height(Spacing.sm))
                        LinearProgressIndicator(progress = { runtime.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    if (!vm.linux.prootAvailable()) {
                        Spacer(Modifier.height(Spacing.md))
                        UnavailableNotice(
                            "PRoot runtime",
                            "This build does not bundle a PRoot loader, so a Linux rootfs cannot be executed. " +
                                "Installation is deliberately refused rather than reporting a fake success. " +
                                "The Android shell below still runs real commands.",
                        )
                    }
                }
            }

            SectionHeader("Detected tools")
            if (scanning && statuses.isEmpty()) {
                LoadingState("Probing each tool by running it...")
            }
            statuses.forEach { st ->
                HarnessCard {
                    Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(st.tool.label, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(Spacing.sm))
                                StatusChip(
                                    if (st.available) "Available" else "Not available",
                                    if (st.available) StatusKind.Ok else StatusKind.Neutral,
                                )
                            }
                            Text(
                                st.tool.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(Spacing.xs))
                            Text(
                                st.version ?: st.detail,
                                style = MonoStyle.copy(fontSize = 11.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            UnavailableNotice(
                "Package installation",
                "Sufyan Harness never marks a tool as installed unless its probe command actually runs. " +
                    "On stock Android only the tools shipped with the system are present; a full toolchain " +
                    "requires the Linux runtime.",
            )
        }
    }
}
