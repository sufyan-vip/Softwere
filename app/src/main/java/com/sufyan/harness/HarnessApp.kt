package com.sufyan.harness

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.sufyan.harness.ai.OpenRouterProvider
import com.sufyan.harness.data.SecureStore
import com.sufyan.harness.data.Settings
import com.sufyan.harness.data.Workspace
import com.sufyan.harness.runtime.LinuxRuntime
import com.sufyan.harness.runtime.Toolchains

class HarnessApp : Application() {

    lateinit var workspace: Workspace
        private set
    lateinit var settings: Settings
        private set
    lateinit var secure: SecureStore
        private set
    lateinit var provider: OpenRouterProvider
        private set
    lateinit var linux: LinuxRuntime
        private set
    lateinit var toolchains: Toolchains
        private set

    override fun onCreate() {
        super.onCreate()
        workspace = Workspace(this)
        settings = Settings(this)
        secure = SecureStore(this)
        provider = OpenRouterProvider(secure)
        linux = LinuxRuntime(this)
        toolchains = Toolchains(linux)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_RUNTIME,
                "Runtime",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows when Sufyan Harness is running a process in the background." }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_RUNTIME = "harness_runtime"
    }
}
