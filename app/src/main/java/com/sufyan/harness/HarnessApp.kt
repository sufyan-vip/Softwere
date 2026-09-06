package com.sufyan.harness

import android.app.Application
import java.io.File
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.sufyan.harness.ai.OpenRouterProvider
import com.sufyan.harness.data.SecureStore
import com.sufyan.harness.data.Settings
import com.sufyan.harness.data.Workspace
import com.sufyan.harness.runtime.AndroidBuildService
import com.sufyan.harness.runtime.Connectivity
import com.sufyan.harness.runtime.CrashLog
import com.sufyan.harness.runtime.EnvHealth
import com.sufyan.harness.runtime.GitHubService
import com.sufyan.harness.runtime.LinuxRuntime
import com.sufyan.harness.runtime.Recovery
import com.sufyan.harness.runtime.RuntimeRepair
import com.sufyan.harness.runtime.TaskRegistry
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
    lateinit var github: GitHubService
        private set
    lateinit var tasks: TaskRegistry
        private set
    lateinit var builder: AndroidBuildService
        private set
    lateinit var envHealth: EnvHealth
        private set
    lateinit var runtimeRepair: RuntimeRepair
        private set
    lateinit var connectivity: Connectivity
        private set
    lateinit var recovery: Recovery
        private set
    lateinit var crashLog: CrashLog
        private set

    override fun onCreate() {
        super.onCreate()
        workspace = Workspace(this)
        settings = Settings(this)
        secure = SecureStore(this)
        provider = OpenRouterProvider(secure)
        linux = LinuxRuntime(this)
        toolchains = Toolchains(linux)
        github = GitHubService(secure)
        tasks = TaskRegistry(this)
        builder = AndroidBuildService(this, linux)
        envHealth = EnvHealth(linux, toolchains)
        runtimeRepair = RuntimeRepair(linux)
        connectivity = Connectivity(this).apply { start() }
        recovery = Recovery(filesDir)
        // Installed first thing so a failure anywhere after this point is reported on next launch.
        crashLog = CrashLog(File(filesDir, "crash")).apply { install() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val runtime = NotificationChannel(
                CHANNEL_RUNTIME,
                "Runtime",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows when Sufyan Harness is running a process in the background." }
            val events = NotificationChannel(
                CHANNEL_EVENTS,
                "Task results",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "Build, AI and runtime tasks that have finished." }
            getSystemService(NotificationManager::class.java).apply {
                createNotificationChannel(runtime)
                createNotificationChannel(events)
            }
        }
    }

    companion object {
        const val CHANNEL_RUNTIME = "harness_runtime"
        const val CHANNEL_EVENTS = "harness_events"
    }
}
