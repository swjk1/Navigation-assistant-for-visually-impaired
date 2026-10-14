package com.navassist.sim.bench

import com.navassist.navcore.strategy.CandidateFeatures
import com.navassist.navcore.strategy.LearnedScorer
import com.navassist.sim.foxglove.MiniJson
import com.navassist.sim.foxglove.arr
import com.navassist.sim.foxglove.json
import com.navassist.sim.foxglove.obj
import java.io.File
import kotlin.math.abs

/**
 * Trains [LearnedScorer] on fork data (ForkData.kt): ridge regression of each option's cost-to-go
 * relative to the mean of its decision's options, on standardised features.
 *
 * Validation is held out by BUILDING, not by record or seed: the last [VALIDATION_BUILDINGS] of each
 * family's buildings. The ridge strength is chosen by validation regret - seconds lost against the
 * best option at each decision, averaged over decisions - and the same regret is reported for the
 * policy that generated the data (B4) and for always exploring, so the model is judged against them.
 */
object TrainScorer {

    const val VALIDATION_BUILDINGS = 2

    private class Row(val group: String, val building: String, val x: DoubleArray, val cost: Double, val chosenByPolicy: Boolean, val explore: Boolean)

    @Suppress("UNCHECKED_CAST")
    fun train(data: File, out: File) {
        // A file still being written ends in a partial line: skip what does not parse.
        val rows = data.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            val r = runCatching { MiniJson.parse(line) as Map<String, Any?> }.getOrNull() ?: return@mapNotNull null
            val episode = r["episode"] as String
            val building = episode.substringBeforeLast('-').substringBeforeLast('-')
            Row(
                group = "$episode/${r["seed"]}/${r["decision"]}",
                building = building,
                x = (r["features"] as List<Number>).map { it.toDouble() }.toDoubleArray(),
                cost = (r["costMean"] as Number).toDouble(),
                chosenByPolicy = r["chosenByPolicy"] as Boolean,
                explore = (r["option"] as Number).toInt() < 0,
            )
        }
        val groups = rows.groupBy { it.group }.filterValues { it.size >= 2 }
        val buildings = rows.map { it.building }.distinct().sorted()
        // The last buildings of each family (by its building number) are validation.
        val validation = buildings.groupBy { it.substringBeforeLast('-') }.values.flatMap { family ->
            family.sortedBy { it.substringAfterLast('-').toIntOrNull() ?: 0 }.takeLast(VALIDATION_BUILDINGS)
        }.toSet()
        val trainGroups = groups.filterValues { it.first().building !in validation }
        val validGroups = groups.filterValues { it.first().building in validation }
        println("training: ${rows.size} records, ${groups.size} decisions with a choice; ${trainGroups.size} train, ${validGroups.size} validation (buildings ${validation.joinToString()})")

        val n = CandidateFeatures.names.size
        fun relative(g: List<Row>): List<Pair<DoubleArray, Double>> {
            val mean = g.map { it.cost }.average()
            return g.map { it.x to it.cost - mean }
        }
        val train = trainGroups.values.flatMap { relative(it) }
        // Standardise every feature but the bias.
        val mean = DoubleArray(n)
        val scale = DoubleArray(n)
        for (j in 1 until n) {
            val col = train.map { it.first[j] }
            mean[j] = col.average()
            val sd = kotlin.math.sqrt(col.sumOf { (it - mean[j]) * (it - mean[j]) } / col.size)
            scale[j] = if (sd > 1e-9) sd else 0.0
        }
        fun z(x: DoubleArray) = DoubleArray(n) { j -> if (j == 0) 1.0 else if (scale[j] > 0) (x[j] - mean[j]) / scale[j] else 0.0 }

        var best: Pair<Double, DoubleArray>? = null
        var bestLambda = 0.0
        for (lambda in listOf(0.1, 1.0, 10.0, 100.0, 1000.0)) {
            val w = ridge(train.map { z(it.first) }, train.map { it.second }, lambda)
            val regret = regret(validGroups.values) { x -> dot(w, z(x)) }
            println("  lambda %-7s validation regret %.2f s".format(lambda, regret))
            if (best == null || regret < best.first) { best = regret to w; bestLambda = lambda }
        }
        val (validRegret, weights) = best!!
        val policyRegret = choiceRegret(validGroups.values) { g -> g.indexOfFirst { it.chosenByPolicy } }
        val exploreRegret = choiceRegret(validGroups.values) { g -> g.indexOfFirst { it.explore } }
        val validMae = validGroups.values.flatMap { relative(it) }.map { abs(dot(weights, z(it.first)) - it.second) }.average()
        println("validation regret: learned %.2f s, B4 (the data's policy) %.2f s, always explore %.2f s; relative-cost MAE %.1f s".format(validRegret, policyRegret, exploreRegret, validMae))
        println("weights:")
        for (j in 0 until n) println("  %-24s %+.3f".format(CandidateFeatures.names[j], weights[j]))

        out.parentFile?.mkdirs()
        out.writeText(json {
            "kind" to "linear relative cost-to-go"
            "features" to arr { for (f in CandidateFeatures.names) add(f) }
            "mean" to arr { for (v in mean) add(v) }
            "scale" to arr { for (v in scale) add(v) }
            "weights" to arr { for (v in weights) add(v) }
            "lambda" to bestLambda
            "trainedOn" to data.path
            "validationBuildings" to arr { for (b in validation.sorted()) add(b) }
            "metrics" to obj {
                "trainDecisions" to trainGroups.size
                "validationDecisions" to validGroups.size
                "validationRegretSeconds" to validRegret
                "policyRegretSeconds" to policyRegret
                "exploreRegretSeconds" to exploreRegret
                "validationMaeSeconds" to validMae
            }
        })
        println("model -> ${out.path}")
    }

    /** Loads a model written by [train]. */
    @Suppress("UNCHECKED_CAST")
    fun load(file: File): LearnedScorer {
        val m = MiniJson.parse(file.readText()) as Map<String, Any?>
        fun floats(key: String) = (m[key] as List<Number>).map { it.toFloat() }.toFloatArray()
        return LearnedScorer((m["features"] as List<String>), floats("mean"), floats("scale"), floats("weights"))
    }

    private fun dot(w: DoubleArray, x: DoubleArray): Double { var s = 0.0; for (i in w.indices) s += w[i] * x[i]; return s }

    /** Mean seconds lost by taking the option [predict] says is cheapest, against the truly cheapest. */
    private fun regret(groups: Collection<List<Row>>, predict: (DoubleArray) -> Double): Double =
        choiceRegret(groups) { g -> g.indices.minByOrNull { predict(g[it].x) } ?: 0 }

    private fun choiceRegret(groups: Collection<List<Row>>, pick: (List<Row>) -> Int): Double = groups.map { g ->
        val i = pick(g).takeIf { it >= 0 } ?: return@map 0.0
        g[i].cost - g.minOf { it.cost }
    }.average()

    /** Solves (X'X + lambda I) w = X'y, not penalising the bias, by Gaussian elimination. */
    private fun ridge(xs: List<DoubleArray>, ys: List<Double>, lambda: Double): DoubleArray {
        val n = xs.first().size
        val a = Array(n) { DoubleArray(n + 1) }
        for ((x, y) in xs.zip(ys)) {
            for (i in 0 until n) {
                for (j in 0 until n) a[i][j] += x[i] * x[j]
                a[i][n] += x[i] * y
            }
        }
        for (i in 1 until n) a[i][i] += lambda
        for (c in 0 until n) {
            val p = (c until n).maxByOrNull { abs(a[it][c]) }!!
            val tmp = a[c]; a[c] = a[p]; a[p] = tmp
            if (abs(a[c][c]) < 1e-12) continue
            for (r in 0 until n) if (r != c) {
                val f = a[r][c] / a[c][c]
                for (k in c..n) a[r][k] -= f * a[c][k]
            }
        }
        return DoubleArray(n) { if (abs(a[it][it]) < 1e-12) 0.0 else a[it][n] / a[it][it] }
    }
}
