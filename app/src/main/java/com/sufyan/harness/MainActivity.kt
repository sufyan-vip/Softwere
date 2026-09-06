package com.sufyan.harness

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sufyan.harness.ui.HarnessRoot
import com.sufyan.harness.ui.theme.SufyanHarnessTheme

class MainActivity : ComponentActivity() {

    private val vm: HarnessViewModel by viewModels()

    /**
     * §51 — notifications are optional: the app asks once, and every notification path checks the
     * grant before posting, so a denial simply means no notifications rather than a broken feature.
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* nothing to undo */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            var mode by remember { mutableStateOf(vm.settings.themeMode) }
            SufyanHarnessTheme(mode) {
                HarnessRoot(vm, onThemeChanged = { mode = it })
            }
        }
    }
}
