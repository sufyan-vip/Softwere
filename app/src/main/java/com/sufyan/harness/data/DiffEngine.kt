package com.sufyan.harness.data

/**
 * §12 — a real unified-diff engine.
 *
 * The Git screen used to depend on a `git` binary, which stock Android does not have; that made
 * "review the AI's changes" impossible on most devices. This computes the diff itself with a proper
 * Myers/LCS pass, so diffs, change review and checkpoint comparison work with no git at all.
 *
 * Pure Kotlin — unit-tested off-device.
 */
object DiffEngine {

    enum class Kind { Context, Added, Removed }

    data class Line(val kind: Kind, val text: String, val oldNo: Int?, val newNo: Int?)

    data class Hunk(val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int, val lines: List<Line>) {
        val header: String get() = "@@ -$oldStart,$oldCount +$newStart,$newCount @@"
    }

    data class FileDiff(
        val path: String,
        val hunks: List<Hunk>,
        val added: Int,
        val removed: Int,
        val binary: Boolean = false,
        val isNew: Boolean = false,
        val isDeleted: Boolean = false,
    ) {
        val changed: Boolean get() = added > 0 || removed > 0 || binary
        fun unified(): String = buildString {
            appendLine("--- ${if (isNew) "/dev/null" else "a/$path"}")
            appendLine("+++ ${if (isDeleted) "/dev/null" else "b/$path"}")
            if (binary) { appendLine("Binary file differs"); return@buildString }
            for (h in hunks) {
                appendLine(h.header)
                for (l in h.lines) {
                    val prefix = when (l.kind) {
                        Kind.Added -> "+"
                        Kind.Removed -> "-"
                        Kind.Context -> " "
                    }
                    appendLine(prefix + l.text)
                }
            }
        }
    }

    /** Longest common subsequence of two line lists, as index pairs. */
    private fun lcs(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return emptyList()
        // Trim the common prefix/suffix first: this is what keeps the O(n*m) table small in practice.
        var start = 0
        while (start < n && start < m && a[start] == b[start]) start++
        var endA = n - 1
        var endB = m - 1
        while (endA >= start && endB >= start && a[endA] == b[endB]) { endA--; endB-- }

        val pairs = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until start) pairs += i to i

        val subA = a.subList(start, endA + 1)
        val subB = b.subList(start, endB + 1)
        if (subA.isNotEmpty() && subB.isNotEmpty()) {
            val rows = subA.size + 1
            val cols = subB.size + 1
            val table = Array(rows) { IntArray(cols) }
            for (i in subA.indices.reversed()) {
                for (j in subB.indices.reversed()) {
                    table[i][j] = if (subA[i] == subB[j]) table[i + 1][j + 1] + 1
                    else maxOf(table[i + 1][j], table[i][j + 1])
                }
            }
            var i = 0
            var j = 0
            while (i < subA.size && j < subB.size) {
                when {
                    subA[i] == subB[j] -> { pairs += (start + i) to (start + j); i++; j++ }
                    table[i + 1][j] >= table[i][j + 1] -> i++
                    else -> j++
                }
            }
        }
        for (k in 0..(n - 1 - endA - 1)) pairs += (endA + 1 + k) to (endB + 1 + k)
        return pairs
    }

    fun splitLines(text: String): List<String> =
        if (text.isEmpty()) emptyList() else text.replace("\r\n", "\n").split("\n").let {
            if (it.lastOrNull() == "") it.dropLast(1) else it
        }

    /**
     * True when text holds bytes no editor should try to render as lines. A NUL character is the
     * same signal git itself uses, so a `.png` that was read as text is reported, never mangled.
     */
    fun isBinary(text: String?): Boolean = text != null && text.contains('\u0000')

    /** Computes a unified diff between [old] and [new] with [context] lines of context. */
    fun diff(path: String, old: String?, new: String?, context: Int = 3): FileDiff {
        if (isBinary(old) || isBinary(new)) {
            return FileDiff(
                path = path,
                hunks = emptyList(),
                added = 0,
                removed = 0,
                isNew = old == null,
                isDeleted = new == null,
                binary = old != new,
            )
        }
        val oldLines = old?.let { splitLines(it) } ?: emptyList()
        val newLines = new?.let { splitLines(it) } ?: emptyList()
        val common = lcs(oldLines, newLines).toMap()

        val all = mutableListOf<Line>()
        var i = 0
        var j = 0
        while (i < oldLines.size || j < newLines.size) {
            val matchJ = common[i]
            when {
                i < oldLines.size && matchJ != null && matchJ == j -> {
                    all += Line(Kind.Context, oldLines[i], i + 1, j + 1); i++; j++
                }
                i < oldLines.size && (matchJ == null || (matchJ > j && j >= newLines.size)) -> {
                    all += Line(Kind.Removed, oldLines[i], i + 1, null); i++
                }
                j < newLines.size && (matchJ == null || matchJ > j) -> {
                    all += Line(Kind.Added, newLines[j], null, j + 1); j++
                }
                i < oldLines.size -> { all += Line(Kind.Removed, oldLines[i], i + 1, null); i++ }
                else -> { all += Line(Kind.Added, newLines[j], null, j + 1); j++ }
            }
        }

        val added = all.count { it.kind == Kind.Added }
        val removed = all.count { it.kind == Kind.Removed }
        val hunks = buildHunks(all, context)
        return FileDiff(
            path = path,
            hunks = hunks,
            added = added,
            removed = removed,
            isNew = old == null,
            isDeleted = new == null,
        )
    }

    private fun buildHunks(all: List<Line>, context: Int): List<Hunk> {
        if (all.none { it.kind != Kind.Context }) return emptyList()
        val interesting = all.indices.filter { all[it].kind != Kind.Context }.toSet()
        val include = sortedSetOf<Int>()
        interesting.forEach { idx ->
            for (k in (idx - context)..(idx + context)) if (k in all.indices) include += k
        }

        val hunks = mutableListOf<Hunk>()
        var chunk = mutableListOf<Int>()
        var last = -99
        for (idx in include) {
            if (chunk.isNotEmpty() && idx != last + 1) {
                hunks += hunkOf(all, chunk)
                chunk = mutableListOf()
            }
            chunk += idx
            last = idx
        }
        if (chunk.isNotEmpty()) hunks += hunkOf(all, chunk)
        return hunks
    }

    private fun hunkOf(all: List<Line>, indices: List<Int>): Hunk {
        val lines = indices.map { all[it] }
        val oldNos = lines.mapNotNull { it.oldNo }
        val newNos = lines.mapNotNull { it.newNo }
        return Hunk(
            oldStart = oldNos.minOrNull() ?: 0,
            oldCount = oldNos.size,
            newStart = newNos.minOrNull() ?: 0,
            newCount = newNos.size,
            lines = lines,
        )
    }

    /** Convenience: the whole diff of a set of files, skipping unchanged ones. */
    fun diffAll(entries: List<Triple<String, String?, String?>>, context: Int = 3): List<FileDiff> =
        entries.map { (path, old, new) -> diff(path, old, new, context) }.filter { it.changed }

    fun summary(diffs: List<FileDiff>): String {
        if (diffs.isEmpty()) return "No changes."
        val added = diffs.sumOf { it.added }
        val removed = diffs.sumOf { it.removed }
        return "${diffs.size} file(s) changed, +$added / -$removed"
    }
}
