package dev.nexa.youtube

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.LinkedHashMap

/**
 * Small, dependency-free signature decipher for the player profiles that still
 * return signatureCipher. Direct URLs are always preferred, so this runs only
 * when a selected client actually requires it.
 */
internal object YoutubeCipher {
    private const val MAX_CACHED_SCRIPTS = 4
    private val scriptCache = object : LinkedHashMap<String, String>(MAX_CACHED_SCRIPTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) =
            size > MAX_CACHED_SCRIPTS
    }

    suspend fun resolveUrl(cipher: String, root: JSONObject? = null): String? {
        val params = try {
            cipher.split("&").associate { param ->
                val parts = param.split("=", limit = 2)
                val key = URLDecoder.decode(parts[0], "UTF-8")
                val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
                key to value
            }
        } catch (_: Throwable) {
            return null
        }
        val baseUrl = params["url"]?.takeIf { it.isNotBlank() } ?: return null
        val sig = params["s"]
        val sp = params["sp"] ?: "sig"

        if (sig.isNullOrBlank()) {
            return baseUrl
        }

        val playerUrl = root?.optJSONObject("assets")?.optString("js")
            ?.takeIf { it.isNotBlank() }
            ?: root?.optString("playerUrl")?.takeIf { it.isNotBlank() }

        if (playerUrl != null) {
            try {
                val deciphered = decipher(playerUrl, sig)
                val separator = if (baseUrl.contains("?")) "&" else "?"
                return "$baseUrl$separator$sp=${URLEncoder.encode(deciphered, "UTF-8")}"
            } catch (_: Throwable) {
                // Decipher fallback
            }
        }

        val separator = if (baseUrl.contains("?")) "&" else "?"
        return "$baseUrl$separator$sp=${URLEncoder.encode(sig, "UTF-8")}"
    }

    suspend fun decipher(playerUrl: String, signature: String): String {
        val code = synchronized(scriptCache) { scriptCache[playerUrl] }
            ?: fetch(playerUrl).also { synchronized(scriptCache) { scriptCache[playerUrl] = it } }
        val function = findSignatureFunction(code)
            ?: throw IllegalStateException("YouTube signature function was not found")
        val operations = parseOperations(code, function)
        if (operations.isEmpty()) throw IllegalStateException("YouTube signature function was empty")
        val value = signature.toMutableList()
        operations.forEach { operation -> operation.apply(value) }
        return value.joinToString("")
    }

    private suspend fun fetch(playerUrl: String): String {
        val resolvedUrl = if (playerUrl.startsWith("//")) {
            "https:$playerUrl"
        } else if (playerUrl.startsWith("/")) {
            "https://www.youtube.com$playerUrl"
        } else {
            playerUrl
        }
        return YoutubeNetwork.text(
            url = resolvedUrl,
            headers = mapOf("User-Agent" to PUBLIC_USER_AGENT),
            maxResponseBytes = 16L * 1024L * 1024L,
        ).takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("YouTube player JavaScript was empty")
    }

    private fun findSignatureFunction(code: String): JavaScriptFunction? {
        val assignment = Regex(
            "(?s)(?:^|[,;])\\s*(?:var\\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\\s*=\\s*function\\s*\\(\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\)\\s*\\{(.*?)\\}\\s*[,;]",
        )
        val declaration = Regex(
            "(?s)function\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\)\\s*\\{(.*?)\\}",
        )
        val candidates = (assignment.findAll(code) + declaration.findAll(code)).map { match ->
            JavaScriptFunction(
                name = match.groupValues[1],
                parameter = match.groupValues[2],
                body = match.groupValues[3],
            )
        }
        return candidates.firstOrNull { function ->
            function.body.contains("split(\"\")") ||
                function.body.contains("split('')")
        }?.takeIf { function ->
            function.body.contains("join(\"\")") || function.body.contains("join('')")
        }
    }

    private fun parseOperations(code: String, function: JavaScriptFunction): List<Operation> {
        val parameter = Regex.escape(function.parameter)
        val matches = mutableListOf<Pair<Int, Operation>>()

        Regex("$parameter\\.reverse\\(\\)").findAll(function.body).forEach {
            matches += it.range.first to Operation.Reverse
        }
        Regex("$parameter\\.splice\\(\\s*0\\s*,\\s*(\\d+)\\s*\\)").findAll(function.body).forEach {
            matches += it.range.first to Operation.Drop(it.groupValues[1].toInt())
        }
        Regex("$parameter\\s*=\\s*$parameter\\.slice\\(\\s*(\\d+)\\s*\\)").findAll(function.body).forEach {
            matches += it.range.first to Operation.Slice(it.groupValues[1].toInt())
        }
        Regex("$parameter\\[(\\d+)\\]\\s*=\\s*$parameter\\[(\\d+)%$parameter\\.length\\]")
            .findAll(function.body)
            .forEach { matches += it.range.first to Operation.Swap(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

        Regex("([A-Za-z_$][A-Za-z0-9_$]*)\\.([A-Za-z_$][A-Za-z0-9_$]*)\\(\\s*$parameter(?:\\s*,\\s*(\\d+))?\\s*\\)")
            .findAll(function.body)
            .forEach { match ->
                val operation = helperOperation(code, match.groupValues[1], match.groupValues[2], match.groupValues[3])
                if (operation != null) matches += match.range.first to operation
            }
        return matches.sortedBy { it.first }.map { it.second }
    }

    private fun helperOperation(
        code: String,
        objectName: String,
        methodName: String,
        argument: String,
    ): Operation? {
        val objectPattern = Regex(
            "(?s)(?:var\\s+|let\\s+|const\\s+)?${Regex.escape(objectName)}\\s*=\\s*\\{(.*?)\\}",
        )
        val objectBody = objectPattern.find(code)?.groupValues?.getOrNull(1) ?: return null
        val methodPattern = Regex(
            "(?s)(?:^|,)\\s*${Regex.escape(methodName)}\\s*:\\s*function\\s*\\([^)]*\\)\\s*\\{(.*?)\\}",
        )
        val body = methodPattern.find(objectBody)?.groupValues?.getOrNull(1) ?: return null
        return when {
            body.contains(".reverse()") -> Operation.Reverse
            body.contains(".splice(0") -> Operation.Drop(argument.toIntOrNull() ?: 0)
            body.contains(".slice(") -> Operation.Slice(argument.toIntOrNull() ?: 0)
            else -> null
        }
    }

    private data class JavaScriptFunction(
        val name: String,
        val parameter: String,
        val body: String,
    )

    private sealed interface Operation {
        fun apply(value: MutableList<Char>)

        data object Reverse : Operation {
            override fun apply(value: MutableList<Char>) = value.reverse()
        }

        data class Drop(val count: Int) : Operation {
            override fun apply(value: MutableList<Char>) {
                repeat(count.coerceAtMost(value.size)) { value.removeAt(0) }
            }
        }

        data class Slice(val count: Int) : Operation {
            override fun apply(value: MutableList<Char>) {
                val count = count.coerceIn(0, value.size)
                repeat(count) { value.removeAt(0) }
            }
        }

        data class Swap(val first: Int, val second: Int) : Operation {
            override fun apply(value: MutableList<Char>) {
                if (value.isEmpty()) return
                val firstIndex = first % value.size
                val secondIndex = second % value.size
                val temporary = value[firstIndex]
                value[firstIndex] = value[secondIndex]
                value[secondIndex] = temporary
            }
        }
    }

    private const val PUBLIC_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
}
