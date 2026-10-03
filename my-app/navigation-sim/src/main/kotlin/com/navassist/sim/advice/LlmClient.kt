package com.navassist.sim.advice

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.navassist.sim.foxglove.MiniJson
import com.navassist.sim.foxglove.arr
import com.navassist.sim.foxglove.json
import com.navassist.sim.foxglove.obj
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * A language model for the adviser, with a reply cache on disk, so a benchmark can be re-run for
 * free and gives the same answers twice. Two providers:
 *  - Gemini over REST, the provider the app already uses (src/services/perceptionEngine.js);
 *  - Claude through the Anthropic Java SDK, with the reply held to a JSON schema.
 *
 * Shared by every run of a benchmark (thread-safe): one rate limit for all of them.
 *
 * @param cacheOnly never call the network; a prompt not in the cache gets no advice.
 */
class LlmClient(
    private val provider: Provider,
    private val model: String,
    private val apiKey: String?,
    private val cacheDir: File,
    private val minIntervalMillis: Long = 250,
    private val cacheOnly: Boolean = false,
    /** Hard spending cap for live Claude calls, in US dollars; null for none. */
    private val budgetUsd: Double? = null,
) {
    enum class Provider { GEMINI, CLAUDE }

    /** Tokens billed by live Claude calls (all runs, from the ledger), and calls refused over budget. */
    val inputTokens = java.util.concurrent.atomic.AtomicLong()
    val outputTokens = java.util.concurrent.atomic.AtomicLong()
    val refusedOverBudget = AtomicInteger()

    /** Dollars per million tokens, input and output (claude-api model table, 2026-06). */
    private val price: Pair<Double, Double>? = when {
        model.startsWith("claude-haiku-4-5") -> 1.0 to 5.0
        model.startsWith("claude-sonnet-5") -> 2.0 to 10.0
        model.startsWith("claude-opus-5") -> 5.0 to 25.0
        else -> null
    }

    /** What the live Claude calls have cost so far, in US dollars. */
    fun spentUsd(): Double = price?.let { (i, o) -> (inputTokens.get() * i + outputTokens.get() * o) / 1e6 } ?: 0.0

    /**
     * Running token totals for this model across every benchmark ever run with it, so the budget
     * caps total spend, not spend per process. Two numbers: input and output tokens.
     */
    private val ledger = File(File(cacheDir, model), "spend.txt")

    init {
        require(budgetUsd == null || provider != Provider.CLAUDE || price != null) {
            "no price known for $model, so the budget cannot be enforced"
        }
        if (ledger.exists()) {
            val (i, o) = ledger.readText().trim().split(' ').map { it.toLong() }
            inputTokens.set(i)
            outputTokens.set(o)
        }
    }

    private fun recordSpend(input: Long, output: Long) = synchronized(ledger) {
        inputTokens.addAndGet(input)
        outputTokens.addAndGet(output)
        ledger.parentFile.mkdirs()
        ledger.writeText("${inputTokens.get()} ${outputTokens.get()}")
    }

    /** One answer: the text, how long it took, and whether it came from the cache. */
    data class Reply(val text: String, val latencyMillis: Long, val cached: Boolean, val promptChars: Int)

    val calls = AtomicInteger()
    val cacheHits = AtomicInteger()
    val failures = AtomicInteger()
    private val latencies = java.util.Collections.synchronizedList(ArrayList<Long>())

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val claude: AnthropicClient? by lazy {
        if (provider != Provider.CLAUDE || apiKey.isNullOrBlank()) null
        // The SDK retries 429 and 5xx itself, honouring retry-after.
        else AnthropicOkHttpClient.builder().apiKey(apiKey).maxRetries(MAX_ATTEMPTS).build()
    }
    private val lock = Any()
    private var lastCallAt = 0L

    /**
     * @param schema JSON schema the reply must follow. Claude enforces it; Gemini is only asked
     *   for JSON and takes the shape from the prompt.
     */
    fun complete(prompt: String, schema: Map<String, Any?>? = null): Reply? {
        val file = File(File(cacheDir, model), sha256(prompt) + ".txt")
        if (file.exists()) {
            cacheHits.incrementAndGet()
            val lines = file.readText().split('\n', limit = 2)
            return Reply(lines.getOrElse(1) { "" }, lines[0].toLongOrNull() ?: 0, cached = true, promptChars = prompt.length)
        }
        if (cacheOnly || apiKey.isNullOrBlank()) return null
        return when (provider) {
            Provider.CLAUDE -> completeWithClaude(prompt, schema, file)
            Provider.GEMINI -> completeWithGemini(prompt, file)
        }
    }

    private fun completeWithGemini(prompt: String, file: File): Reply? {
        var backoff = 1000L
        repeat(MAX_ATTEMPTS) {
            pace()
            calls.incrementAndGet()
            val started = System.currentTimeMillis()
            val response = runCatching { http.send(geminiRequest(prompt), HttpResponse.BodyHandlers.ofString()) }.getOrNull()
            val latency = System.currentTimeMillis() - started
            when {
                response == null || response.statusCode() == 429 || response.statusCode() >= 500 -> {
                    Thread.sleep(backoff)
                    backoff *= 2
                }
                response.statusCode() != 200 -> {
                    failures.incrementAndGet()
                    System.err.println("LLM HTTP ${response.statusCode()}: ${response.body().take(300)}")
                    return null
                }
                else -> {
                    val text = textOf(response.body()) ?: run { failures.incrementAndGet(); return null }
                    latencies += latency
                    store(file, prompt, latency, text)
                    return Reply(text, latency, cached = false, promptChars = prompt.length)
                }
            }
        }
        failures.incrementAndGet()
        return null
    }

    private fun completeWithClaude(prompt: String, schema: Map<String, Any?>?, file: File): Reply? {
        val client = claude ?: return null
        // Budget check before each call. Calls already in flight on other threads can overshoot
        // by a fraction of a cent each; the CLI sets the cap with that margin.
        if (budgetUsd != null && spentUsd() >= budgetUsd) {
            refusedOverBudget.incrementAndGet()
            return null
        }
        val haiku = model.startsWith("claude-haiku")
        val output = OutputConfig.builder()
        // A short judgement asked every few seconds while walking: low effort. Haiku 4.5 takes no
        // effort setting (it errors), and runs without thinking.
        if (!haiku) output.effort(OutputConfig.Effort.LOW)
        if (schema != null) {
            val jsonSchema = JsonOutputFormat.Schema.builder()
            for ((k, v) in schema) jsonSchema.putAdditionalProperty(k, JsonValue.from(v))
            output.format(JsonOutputFormat.builder().schema(jsonSchema.build()).build())
        }
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4000L)
            .addUserMessage(prompt)
            .outputConfig(output.build())
            .apply { if (haiku) temperature(0.0) }
            .build()
        pace()
        calls.incrementAndGet()
        val started = System.currentTimeMillis()
        val message = try {
            client.messages().create(params)
        } catch (e: AnthropicServiceException) {
            failures.incrementAndGet()
            System.err.println("Claude HTTP ${e.statusCode()}: ${e.message?.take(300)}")
            return null
        } catch (e: AnthropicException) {
            failures.incrementAndGet()
            System.err.println("Claude: ${e.message?.take(300)}")
            return null
        }
        val latency = System.currentTimeMillis() - started
        recordSpend(message.usage().inputTokens(), message.usage().outputTokens())
        val text = message.content().joinToString("") { block -> block.text().map { it.text() }.orElse("") }
        if (text.isBlank()) {
            failures.incrementAndGet()
            System.err.println("Claude: no text, stop reason ${message.stopReason().orElse(null)}")
            return null
        }
        latencies += latency
        store(file, prompt, latency, text)
        return Reply(text, latency, cached = false, promptChars = prompt.length)
    }

    /** Spaces calls at least [minIntervalMillis] apart across all threads. */
    private fun pace() = synchronized(lock) {
        val wait = lastCallAt + minIntervalMillis - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastCallAt = System.currentTimeMillis()
    }

    /** The reply, and beside it the prompt that produced it, for reading back later. */
    private fun store(file: File, prompt: String, latency: Long, text: String) {
        file.parentFile.mkdirs()
        file.writeText("$latency\n$text")
        File(file.parentFile, file.nameWithoutExtension + ".prompt.txt").writeText(prompt)
    }

    /** Median and 90th percentile latency of live calls, in milliseconds. */
    fun latencySummary(): Pair<Long, Long>? = synchronized(latencies) {
        if (latencies.isEmpty()) return null
        val sorted = latencies.sorted()
        sorted[sorted.size / 2] to sorted[(sorted.size * 9) / 10]
    }

    private fun geminiRequest(prompt: String): HttpRequest = HttpRequest.newBuilder()
        .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent"))
        .timeout(Duration.ofSeconds(30))
        .header("content-type", "application/json")
        .header("x-goog-api-key", apiKey ?: "")
        .POST(
            HttpRequest.BodyPublishers.ofString(
                json {
                    "contents" to arr { add(obj { "role" to "user"; "parts" to arr { add(obj { "text" to prompt }) } }) }
                    "generationConfig" to obj {
                        "temperature" to 0
                        "maxOutputTokens" to 300
                        "responseMimeType" to "application/json"
                        "thinkingConfig" to obj { "thinkingBudget" to 0 }
                    }
                },
            ),
        )
        .build()

    @Suppress("UNCHECKED_CAST")
    private fun textOf(body: String): String? = runCatching {
        val root = MiniJson.parse(body) as Map<String, Any?>
        val candidate = (root["candidates"] as List<Map<String, Any?>>).first()
        val parts = (candidate["content"] as Map<String, Any?>)["parts"] as List<Map<String, Any?>>
        parts.joinToString("") { it["text"] as? String ?: "" }
    }.getOrNull()

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_ATTEMPTS = 6

        /** [name] from the environment, else from the app's .env next to this project. */
        fun keyFromEnvironment(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
            ?: File("../.env").takeIf { it.exists() }?.readLines()
                ?.firstNotNullOfOrNull { Regex("""^\s*$name\s*=\s*(.*?)\s*$""").find(it)?.groupValues?.get(1) }
                ?.trim('"', '\'')?.takeIf { it.isNotBlank() }
    }
}
