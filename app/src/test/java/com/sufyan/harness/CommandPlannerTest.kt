package com.sufyan.harness

import com.sufyan.harness.ai.CommandPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §46 — the planner may only offer commands it can prove exist. These tests are the guard against
 * the app ever inventing `npm run build` for a project that has no such script.
 */
class CommandPlannerTest {

    @Test
    fun `no evidence means no commands`() {
        val plan = CommandPlanner.plan(setOf("README.md", "notes.txt"))
        assertTrue(plan.isEmpty)
        assertTrue(plan.notes.any { it.contains("No build system") })
    }

    @Test
    fun `npm scripts are only offered when declared`() {
        val pkg = """{"name":"x","scripts":{"dev":"vite","build":"vite build"}}"""
        val plan = CommandPlanner.plan(setOf("package.json", "index.html"), pkg)
        assertEquals("npm run dev", plan.of("dev")?.command)
        assertEquals("npm run build", plan.of("build")?.command)
        assertNull(plan.of("test"))
    }

    @Test
    fun `every command carries evidence`() {
        val pkg = """{"scripts":{"test":"jest"}}"""
        val plan = CommandPlanner.plan(setOf("package.json", "package-lock.json"), pkg)
        assertTrue(plan.commands.isNotEmpty())
        assertTrue(plan.commands.all { it.evidence.isNotBlank() })
        assertEquals("package.json scripts.test", plan.of("test")?.evidence)
    }

    @Test
    fun `lockfile decides the package manager`() {
        val pkg = """{"scripts":{"build":"tsc"}}"""
        assertEquals("pnpm install", CommandPlanner.plan(setOf("package.json", "pnpm-lock.yaml"), pkg).of("install")?.command)
        assertEquals("yarn install", CommandPlanner.plan(setOf("package.json", "yarn.lock"), pkg).of("install")?.command)
        assertEquals("npm install", CommandPlanner.plan(setOf("package.json"), pkg).of("install")?.command)
    }

    @Test
    fun `gradle wrapper is preferred over a system gradle`() {
        val withWrapper = CommandPlanner.plan(setOf("build.gradle.kts", "settings.gradle.kts", "gradlew"))
        assertEquals("./gradlew assembleDebug", withWrapper.of("build")?.command)
        assertTrue(withWrapper.notes.isEmpty())

        val withoutWrapper = CommandPlanner.plan(setOf("build.gradle", "settings.gradle"))
        assertEquals("gradle assembleDebug", withoutWrapper.of("build")?.command)
        assertTrue(withoutWrapper.notes.any { it.contains("wrapper") })
    }

    @Test
    fun `a plain html folder gets the built-in server only`() {
        val plan = CommandPlanner.plan(setOf("index.html", "style.css"))
        assertEquals("Static site", plan.stack)
        assertNotNull(plan.of("dev"))
        assertNull(plan.of("build"))
    }

    @Test
    fun `missing node_modules is reported as a note`() {
        val pkg = """{"scripts":{"dev":"vite"}}"""
        val plan = CommandPlanner.plan(setOf("package.json"), pkg)
        assertTrue(plan.notes.any { it.contains("not installed") })
    }

    @Test
    fun `python entry point is detected`() {
        val plan = CommandPlanner.plan(setOf("requirements.txt", "main.py"))
        assertEquals("Python", plan.stack)
        assertEquals("pip install -r requirements.txt", plan.of("install")?.command)
        assertEquals("python3 main.py", plan.of("run")?.command)
    }

    @Test
    fun `scriptsOf tolerates nesting and whitespace`() {
        val pkg = """
            {
              "name": "demo",
              "scripts": {
                "build" : "webpack --config w.js",
                "test": "jest"
              },
              "dependencies": { "react": "^18" }
            }
        """.trimIndent()
        assertEquals(setOf("build", "test"), CommandPlanner.scriptsOf(pkg))
    }

    @Test
    fun `scriptsOf returns nothing when the block is absent`() {
        assertTrue(CommandPlanner.scriptsOf("""{"name":"x"}""").isEmpty())
    }
}
