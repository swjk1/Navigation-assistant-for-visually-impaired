package com.navassist.sim.advice

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
import java.util.concurrent.atomic.AtomicLong

/**
 * OpenAI for the long-term planner's model baselines: the Decisions API (B5, scoring candidates)
 * and the Responses API (B2/B3, choosing a candidate). One client per process, shared by every run.
 *
 * Nothing here spends money by accident:
 *  - a live call needs OPENAI_API_KEY *and* NAV_LLM_ALLOW=1; without the second, only cached
 *    replies are used and anything uncached is answered with null (the planner falls back to its
 *    heuristic, so the run still completes);
 *  - NAV_LLM_DRY_RUN=1 sends nothing: it counts the requests and estimates their tokens, for a cost
 *    quote before any paid run;
 *  - LLM_BUDGET_USD (default 1.00) caps spend across runs, using a ledger beside the cache;
 *  - every reply is cached by the hash of its request, so a run can be replayed for free.
 *
 * The Decisions API is in public beta: POST /v1/decisions, model gpt-6-luna, input billed at
 * $0.10 per million tokens, no output charge (developers.openai.com, checked 2026-10-14). The
 * Responses API model and its prices come from OPENAI_MODEL and OPENAI_PRICE_IN / OPENAI_PRICE_OUT
 * (dollars per million tokens); without a price, live calls to it are refused, since the budget
 * could not be enforced.
 */
class OpenAiClient(
    private val apiKey: String? = LlmClient.keyFromEnvironment("OPENAI_API_KEY"),
    private val cacheDir: File = File("benchmarks/llm-cache"),
    val decisionsModel: String = System.getenv("OPENAI_DECISIONS_MODEL") ?: "gpt-6-luna",
    val responsesModel: String? = System.getenv("OPENAI_MODEL"),
    private val allowLive: Boolean = System.getenv("NAV_LLM_ALLOW") == "1",
    val dryRun: Boolean = System.getenv("NAV_LLM_DRY_RUN") == "1",
    private val budgetUsd: Double = System.getenv("LLM_BUDGET_USD")?.toDouble() ?: 1.0,
    private val minIntervalMillis: Long = 200,
) {
    val requests = AtomicInteger()
    val cacheHits = AtomicInteger()
    val failures = AtomicInteger()
    val refused = AtomicInteger()
    val dryRunRequests = AtomicInteger()
    val estimatedInputTokens = AtomicLong()
    val estimatedOutputTokens = AtomicLong()
    private val latencies = java.util.Collections.synchronizedList(ArrayList<Long>())

    private val responsesPrice: Pair<Double, Double>? = run {
        val i = System.getenv("OPENAI_PRICE_IN")?.toDoubleOrNull()
        val o = System.getenv("OPENAI_PRICE_OUT")?.toDoubleOrNull()
        if (i != null && o != null) i to o else null
    }

    private val ledger = File(cacheDir, "openai-spend.txt")
    private val spentMicros = AtomicLong(if (ledger.exists()) ledger.readText().trim().toLongOrNull() ?: 0 else 0)

    fun spentUsd(): Double = spentMicros.get() / 1e6

    private fun charge(usd: Double) = synchronized(ledger) {
        spentMicros.addAndGet((usd * 1e6).toLong())
        ledger.parentFile.mkdirs()
        ledger.writeText(spentMicros.get().toString())
    }

    /** What the dry-run requests would cost, in dollars, at the known prices. */
    fun dryRunEstimateUsd(decisionsShare: Double = 1.0): Double {
        val input = estimatedInputTokens.get().toDouble()
        val output = estimatedOutputTokens.get().toDouble()
        val decisions = input * decisionsShare * DECISIONS_INPUT_PER_MILLION / 1e6
        val responses = responsesPrice?.let { (i, o) -> input * (1 - decisionsShare) * i / 1e6 + output * o / 1e6 } ?: 0.0
        return decisions + responses
    }

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val lock = Any()
    private var lastCallAt = 0L

    /** One choice question to the Decisions API: the probability of each choice, by value. */
    fun choose(input: String, name: String, instructions: String, choices: List<Pair<String, String>>): Map<String, Float>? {
        val body = json {
            "model" to decisionsModel
            "input" to input
            "questions" to arr {
                add(obj {
                    "type" to "choice"
                    "name" to name
                    "instructions" to instructions
                    "choices" to arr { for ((value, description) in choices) add(obj { "value" to value; "description" to description }) }
                })
            }
        }
        val reply = send("decisions", "https://api.openai.com/v1/decisions", body, decisionsModel, estimatedOutput = 0) { tokensIn, _ ->
            tokensIn * DECISIONS_INPUT_PER_MILLION / 1e6
        } ?: return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val answers = (MiniJson.parse(reply) as Map<String, Any?>)["answers"] as List<Map<String, Any?>>
            val answer = answers.first { it["name"] == name }
            if (answer["type"] == "refusal") return null
            @Suppress("UNCHECKED_CAST")
            (answer["probabilities"] as List<Map<String, Any?>>).associate { (it["value"] as String) to (it["probability"] as Number).toFloat() }
        }.getOrElse { failures.incrementAndGet(); null }
    }

    /** One structured reply from the Responses API, held to [schema]; the JSON text. */
    fun respond(prompt: String, schemaName: String, schema: Map<String, Any?>): String? {
        val model = responsesModel ?: return null
        val body = json {
            "model" to model
            "input" to prompt
            "text" to obj {
                "format" to obj {
                    "type" to "json_schema"
                    "name" to schemaName
                    "strict" to true
                    "schema" to toJson(schema)
                }
            }
        }
        val reply = send("responses", "https://api.openai.com/v1/responses", body, model, estimatedOutput = 80) { tokensIn, tokensOut ->
            val (i, o) = responsesPrice ?: return@send null
            (tokensIn * i + tokensOut * o) / 1e6
        } ?: return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val root = MiniJson.parse(reply) as Map<String, Any?>
            (root["output_text"] as? String) ?: (root["output"] as List<Map<String, Any?>>).flatMap { item ->
                (item["content"] as? List<Map<String, Any?>>).orEmpty()
            }.firstNotNullOfOrNull { it["text"] as? String }
        }.getOrElse { failures.incrementAndGet(); null }
    }

    /**
     * Cache, then gate, then send. [price] turns (input, output) tokens into dollars, or null when
     * the price is unknown (the call is then refused).
     */
    private fun send(
        api: String,
        url: String,
        body: String,
        model: String,
        estimatedOutput: Int,
        price: (Long, Long) -> Double?,
    ): String? {
        val file = File(File(cacheDir, "openai-$api-$model"), sha256(body) + ".json")
        if (file.exists()) {
            cacheHits.incrementAndGet()
            return file.readText()
        }
        val tokensIn = (body.length / CHARS_PER_TOKEN).toLong()
        if (dryRun) {
            dryRunRequests.incrementAndGet()
            estimatedInputTokens.addAndGet(tokensIn)
            estimatedOutputTokens.addAndGet(estimatedOutput.toLong())
            return null
        }
        if (!allowLive || apiKey.isNullOrBlank()) {
            refused.incrementAndGet()
            return null
        }
        val estimate = price(tokensIn, estimatedOutput.toLong())
        if (estimate == null || spentUsd() + estimate > budgetUsd) {
            refused.incrementAndGet()
            return null
        }
        var backoff = 1000L
        repeat(MAX_ATTEMPTS) {
            pace()
            requests.incrementAndGet()
            val started = System.currentTimeMillis()
            val request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json").header("authorization", "Bearer $apiKey")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val response = runCatching { http.send(request, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
            val latency = System.currentTimeMillis() - started
            when {
                response == null || response.statusCode() == 429 || response.statusCode() >= 500 -> {
                    Thread.sleep(backoff)
                    backoff *= 2
                }
                response.statusCode() != 200 -> {
                    failures.incrementAndGet()
                    System.err.println("OpenAI $api HTTP ${response.statusCode()}: ${response.body().take(300)}")
                    return null
                }
                else -> {
                    val text = response.body()
                    val (usedIn, usedOut) = usage(text) ?: (tokensIn to estimatedOutput.toLong())
                    price(usedIn, usedOut)?.let { charge(it) }
                    latencies += latency
                    file.parentFile.mkdirs()
                    file.writeText(text)
                    File(file.parentFile, file.nameWithoutExtension + ".request.json").writeText(body)
                    return text
                }
            }
        }
        failures.incrementAndGet()
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun usage(text: String): Pair<Long, Long>? = runCatching {
        val u = (MiniJson.parse(text) as Map<String, Any?>)["usage"] as? Map<String, Any?> ?: return null
        val i = (u["input_tokens"] ?: u["prompt_tokens"]) as? Number ?: return null
        val o = (u["output_tokens"] ?: u["completion_tokens"]) as? Number
        i.toLong() to (o?.toLong() ?: 0L)
    }.getOrNull()

    private fun pace() = synchronized(lock) {
        val wait = lastCallAt + minIntervalMillis - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastCallAt = System.currentTimeMillis()
    }

    fun latencySummary(): Pair<Long, Long>? = synchronized(latencies) {
        if (latencies.isEmpty()) return null
        val sorted = latencies.sorted()
        sorted[sorted.size / 2] to sorted[(sorted.size * 9) / 10]
    }

    fun report(): String = buildString {
        append("OpenAI: ${requests.get()} live requests, ${cacheHits.get()} cached, ${failures.get()} failures, ${refused.get()} refused")
        append(" (live calls ${if (allowLive) "allowed" else "NOT allowed: set NAV_LLM_ALLOW=1"}; spent so far \$%.4f of \$%.2f)".format(spentUsd(), budgetUsd))
        latencySummary()?.let { (m, p) -> append(", latency median $m ms, p90 $p ms") }
        if (dryRun) {
            append("\nDry run: ${dryRunRequests.get()} requests would be sent, ~${estimatedInputTokens.get()} input and ~${estimatedOutputTokens.get()} output tokens")
            append(", about \$%.4f at the Decisions API's input price".format(estimatedInputTokens.get() * DECISIONS_INPUT_PER_MILLION / 1e6))
        }
    }

    private fun toJson(value: Any?): com.navassist.sim.foxglove.JsonObject = obj {
        @Suppress("UNCHECKED_CAST")
        for ((k, v) in value as Map<String, Any?>) put(this, k, v)
    }

    @Suppress("UNCHECKED_CAST")
    private fun put(o: com.navassist.sim.foxglove.JsonObject, key: String, value: Any?) = with(o) {
        when (value) {
            null -> key to (null as String?)
            is String -> key to value
            is Number -> key to value
            is Boolean -> key to value
            is Map<*, *> -> key to toJson(value)
            is List<*> -> key to arr {
                for (item in value) when (item) {
                    is String -> add(item)
                    is Number -> add(item)
                    is Map<*, *> -> add(toJson(item))
                    else -> add(item.toString())
                }
            }
            else -> key to value.toString()
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        /** Dollars per million input tokens, Decisions API (gpt-6-luna), 2026-10-14. */
        const val DECISIONS_INPUT_PER_MILLION = 0.10
        private const val MAX_ATTEMPTS = 5
        private const val CHARS_PER_TOKEN = 4.0
    }
}
