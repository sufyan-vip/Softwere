package com.sufyan.harness.runtime

import android.util.Base64
import com.sufyan.harness.data.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GitHubUser(val login: String, val name: String?, val avatarUrl: String?)

data class GitHubRepo(
    val fullName: String,
    val name: String,
    val private: Boolean,
    val defaultBranch: String,
    val description: String?,
    val updatedAt: String?,
    val htmlUrl: String,
)

data class GitHubCommit(val sha: String, val message: String, val author: String, val date: String)

data class RemoteFile(val path: String, val sha: String, val size: Long)

/** §32 — one file that differs on both sides since the last sync. */
data class SyncConflict(val path: String, val localExists: Boolean, val remoteExists: Boolean)

sealed interface PushOutcome {
    data class Success(val commitSha: String, val filesPushed: Int) : PushOutcome
    /** The branch moved on the server since [expectedSha]; the caller must resolve before retrying. */
    data class Rejected(val remoteSha: String, val expectedSha: String?, val reason: String) : PushOutcome
}

/**
 * §29-§33 — GitHub over the REST API.
 *
 * Deliberately implemented against the API rather than a `git` binary: stock Android has no git, so
 * an implementation that shelled out would simply never work for most users. Clone, pull, commit,
 * push, branch, history and diff all run through HTTPS here, which works on every device.
 *
 * Security (§30): the token comes from the Keystore-backed [SecureStore], is sent only in an
 * Authorization header to api.github.com, is never written to the terminal, never logged and never
 * placed in the AI conversation. The agent has no GitHub tool at all, so it cannot push or force-push.
 */
class GitHubService(private val secure: SecureStore) {

    companion object {
        private const val API = "https://api.github.com"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun hasToken(): Boolean = secure.hasGithubToken()

    private fun token(): String = secure.githubToken()
        ?: throw IllegalStateException("No GitHub token saved. Connect GitHub in Settings first.")

    private fun Request.Builder.authed(): Request.Builder = this
        .header("Authorization", "Bearer ${token()}")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "SufyanHarness")

    /** Maps HTTP failures onto messages that say what to do, never a bare status code (§4). */
    private fun errorFor(response: Response, body: String): IOException {
        val apiMessage = runCatching {
            json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        val text = when (response.code) {
            401 -> "GitHub rejected the token (401). It is invalid or has been revoked — create a new one and reconnect."
            403 -> if (body.contains("rate limit", true)) {
                "GitHub rate limit reached (403). Wait for the limit to reset, then retry."
            } else {
                "GitHub refused the request (403). The token is missing the required scope — it needs `repo`."
            }
            404 -> "Not found (404). Either the repository does not exist or the token cannot see it (private repos need the `repo` scope)."
            409 -> "Conflict (409). The repository is empty or the branch does not exist yet."
            422 -> "GitHub rejected the data (422). ${apiMessage ?: "The branch may have moved, or the name is already taken."}"
            in 500..599 -> "GitHub is having problems (${response.code}). Retry in a moment."
            else -> "GitHub request failed (${response.code}). ${apiMessage ?: body.take(200)}"
        }
        return IOException(text)
    }

    private suspend fun request(
        method: String,
        path: String,
        payload: JsonObject? = null,
    ): JsonElement = withContext(Dispatchers.IO) {
        val url = if (path.startsWith("http")) path else API + path
        val body = payload?.let { it.toString().toRequestBody(JSON) }
        val builder = Request.Builder().url(url).authed()
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(body ?: "".toRequestBody(JSON))
            "PATCH" -> builder.patch(body ?: "".toRequestBody(JSON))
            "PUT" -> builder.put(body ?: "".toRequestBody(JSON))
            "DELETE" -> builder.delete()
            else -> throw IllegalArgumentException("Unsupported method $method")
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFor(response, text)
            if (text.isBlank()) JsonNull else json.parseToJsonElement(text)
        }
    }

    // ---- account -----------------------------------------------------------

    /** §29 — verifies the token by really calling the API, then remembers the login. */
    suspend fun connect(newToken: String): Result<GitHubUser> = runCatching {
        secure.setGithubToken(newToken)
        val user = me().getOrElse { secure.clearGithubToken(); throw it }
        secure.githubLogin = user.login
        user
    }

    suspend fun me(): Result<GitHubUser> = runCatching {
        val o = request("GET", "/user").jsonObject
        GitHubUser(
            login = o["login"]!!.jsonPrimitive.content,
            name = o["name"]?.jsonPrimitive?.contentOrNull,
            avatarUrl = o["avatar_url"]?.jsonPrimitive?.contentOrNull,
        )
    }

    fun disconnect() = secure.clearGithubToken()

    // ---- repositories ------------------------------------------------------

    suspend fun listRepos(): Result<List<GitHubRepo>> = runCatching {
        val arr = request("GET", "/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member").jsonArray
        arr.map { it.jsonObject.toRepo() }
    }

    suspend fun createRepo(name: String, private: Boolean, description: String?): Result<GitHubRepo> = runCatching {
        val payload = buildJsonObject {
            put("name", name)
            put("private", private)
            put("auto_init", true)
            if (!description.isNullOrBlank()) put("description", description)
        }
        request("POST", "/user/repos", payload).jsonObject.toRepo()
    }

    private fun JsonObject.toRepo() = GitHubRepo(
        fullName = this["full_name"]!!.jsonPrimitive.content,
        name = this["name"]!!.jsonPrimitive.content,
        private = this["private"]?.jsonPrimitive?.booleanOrNull ?: false,
        defaultBranch = this["default_branch"]?.jsonPrimitive?.contentOrNull ?: "main",
        description = this["description"]?.jsonPrimitive?.contentOrNull,
        updatedAt = this["updated_at"]?.jsonPrimitive?.contentOrNull,
        htmlUrl = this["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )

    suspend fun listBranches(fullName: String): Result<List<String>> = runCatching {
        request("GET", "/repos/$fullName/branches?per_page=100").jsonArray
            .map { it.jsonObject["name"]!!.jsonPrimitive.content }
    }

    suspend fun createBranch(fullName: String, newBranch: String, fromBranch: String): Result<String> = runCatching {
        val sha = headSha(fullName, fromBranch).getOrThrow()
        val payload = buildJsonObject {
            put("ref", "refs/heads/$newBranch")
            put("sha", sha)
        }
        request("POST", "/repos/$fullName/git/refs", payload)
        sha
    }

    suspend fun listCommits(fullName: String, branch: String, limit: Int = 30): Result<List<GitHubCommit>> = runCatching {
        request("GET", "/repos/$fullName/commits?sha=$branch&per_page=$limit").jsonArray.map { el ->
            val o = el.jsonObject
            val commit = o["commit"]!!.jsonObject
            GitHubCommit(
                sha = o["sha"]!!.jsonPrimitive.content,
                message = commit["message"]?.jsonPrimitive?.contentOrNull?.lineSequence()?.first().orEmpty(),
                author = commit["author"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                date = commit["author"]?.jsonObject?.get("date")?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }
    }

    /** The commit sha the branch currently points at on the server. */
    suspend fun headSha(fullName: String, branch: String): Result<String> = runCatching {
        val o = request("GET", "/repos/$fullName/git/ref/heads/$branch").jsonObject
        o["object"]!!.jsonObject["sha"]!!.jsonPrimitive.content
    }

    /** Every path in the branch's tree, with its blob sha — used to detect remote-side changes. */
    suspend fun listTree(fullName: String, branch: String): Result<List<RemoteFile>> = runCatching {
        val head = headSha(fullName, branch).getOrThrow()
        val commit = request("GET", "/repos/$fullName/git/commits/$head").jsonObject
        val treeSha = commit["tree"]!!.jsonObject["sha"]!!.jsonPrimitive.content
        val tree = request("GET", "/repos/$fullName/git/trees/$treeSha?recursive=1").jsonObject
        tree["tree"]!!.jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            if (o["type"]?.jsonPrimitive?.contentOrNull != "blob") return@mapNotNull null
            RemoteFile(
                path = o["path"]!!.jsonPrimitive.content,
                sha = o["sha"]!!.jsonPrimitive.content,
                size = o["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }

    /** Contents of one remote file, decoded. Used by the conflict resolver (§32). */
    suspend fun fetchFile(fullName: String, path: String, ref: String): Result<String> = runCatching {
        val o = request("GET", "/repos/$fullName/contents/$path?ref=$ref").jsonObject
        val encoded = o["content"]?.jsonPrimitive?.contentOrNull.orEmpty().replace("\n", "")
        String(Base64.decode(encoded, Base64.DEFAULT))
    }

    // ---- clone / pull ------------------------------------------------------

    /**
     * Downloads the branch as a zipball into [dest]. This is a real clone of the tree contents (no
     * history), which is what the on-device workspace can use; it is described as such in the UI.
     */
    suspend fun downloadZip(fullName: String, branch: String, dest: File): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$API/repos/$fullName/zipball/$branch")
                    .authed()
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw errorFor(response, response.body?.string().orEmpty())
                    }
                    val body = response.body ?: throw IOException("GitHub returned an empty response.")
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { out -> body.byteStream().copyTo(out) }
                }
                if (dest.length() == 0L) throw IOException("The downloaded archive is empty.")
                dest.length()
            }
        }

    // ---- commit / push -----------------------------------------------------

    /**
     * Creates a real commit from [files] (path → content) using the Git Data API, then moves the
     * branch. [expectedSha] is the commit the local copy was based on: when the branch has moved
     * since, the push is [PushOutcome.Rejected] instead of silently overwriting someone's work (§32).
     * A force push is only performed when [force] is explicitly set by the user (§33).
     */
    suspend fun pushFiles(
        fullName: String,
        branch: String,
        message: String,
        files: Map<String, ByteArray>,
        deletions: List<String> = emptyList(),
        expectedSha: String? = null,
        force: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): Result<PushOutcome> = runCatching {
        require(message.isNotBlank()) { "A commit message is required." }
        require(files.isNotEmpty() || deletions.isNotEmpty()) { "There is nothing to commit." }

        onProgress("Reading remote branch...")
        val remoteHead = headSha(fullName, branch).getOrNull()

        if (remoteHead != null && expectedSha != null && remoteHead != expectedSha && !force) {
            return@runCatching PushOutcome.Rejected(
                remoteSha = remoteHead,
                expectedSha = expectedSha,
                reason = "The branch \u201c$branch\u201d moved on GitHub since this project was last synchronised.",
            )
        }

        val baseTree = if (remoteHead != null) {
            val commit = request("GET", "/repos/$fullName/git/commits/$remoteHead").jsonObject
            commit["tree"]!!.jsonObject["sha"]!!.jsonPrimitive.content
        } else null

        val treeEntries = mutableListOf<JsonObject>()
        var index = 0
        for ((path, bytes) in files) {
            index++
            onProgress("Uploading $index/${files.size}: $path")
            val blob = request(
                "POST", "/repos/$fullName/git/blobs",
                buildJsonObject {
                    put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    put("encoding", "base64")
                },
            ).jsonObject["sha"]!!.jsonPrimitive.content
            treeEntries += buildJsonObject {
                put("path", path)
                put("mode", "100644")
                put("type", "blob")
                put("sha", blob)
            }
        }
        for (path in deletions) {
            treeEntries += buildJsonObject {
                put("path", path)
                put("mode", "100644")
                put("type", "blob")
                put("sha", JsonNull)
            }
        }

        onProgress("Creating tree...")
        val treeSha = request(
            "POST", "/repos/$fullName/git/trees",
            buildJsonObject {
                if (baseTree != null) put("base_tree", baseTree)
                put("tree", JsonArray(treeEntries))
            },
        ).jsonObject["sha"]!!.jsonPrimitive.content

        onProgress("Creating commit...")
        val commitSha = request(
            "POST", "/repos/$fullName/git/commits",
            buildJsonObject {
                put("message", message)
                put("tree", treeSha)
                put("parents", JsonArray(listOfNotNull(remoteHead).map { JsonPrimitive(it) }))
            },
        ).jsonObject["sha"]!!.jsonPrimitive.content

        onProgress("Updating branch...")
        try {
            request(
                "PATCH", "/repos/$fullName/git/refs/heads/$branch",
                buildJsonObject {
                    put("sha", commitSha)
                    put("force", force)
                },
            )
        } catch (e: IOException) {
            if (remoteHead == null) {
                // Branch does not exist yet — create it.
                request(
                    "POST", "/repos/$fullName/git/refs",
                    buildJsonObject {
                        put("ref", "refs/heads/$branch")
                        put("sha", commitSha)
                    },
                )
            } else {
                throw e
            }
        }
        PushOutcome.Success(commitSha, files.size)
    }
}
