package com.sufyan.harness

import android.os.Bundle
import androidx.activity.ComponentActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var mode by remember { mutableStateOf(vm.settings.themeMode) }
            SufyanHarnessTheme(mode) {
                HarnessRoot(vm, onThemeChanged = { mode = it })
            }
        }
    }
}
