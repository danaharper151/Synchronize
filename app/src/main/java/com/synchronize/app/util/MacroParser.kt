package com.synchronize.app.util

import android.util.Log

/**
 * Parses voice-transcribed macro entries in the format:
 * calories / fat / protein / net carbs / fiber
 *
 * Supports flexible natural language input:
 * - "350 calories, 28 fat, 22 protein, 4 net carbs, 12 fiber"
 * - "350, 28, 22, 4, 12"
 * - "350 cal 28 fat 22 protein 4 carbs 12 fiber"
 * - "28 fat 22 protein 4 net carbs 12 fiber 350 calories"
 */
object MacroParser {

    private const val TAG = "MacroParser"

    data class ParsedMacros(
        val calories: Int,
        val fatGrams: Double,
        val proteinGrams: Double,
        val netCarbGrams: Double,
        val fiberGrams: Double,
        val totalCarbGrams: Double = netCarbGrams + fiberGrams,
        val ketogenicRatio: Double = if ((netCarbGrams + proteinGrams) > 0)
            fatGrams / (netCarbGrams + proteinGrams) else 0.0,
        val parsingMethod: String = "REGEX"
    )

    // ── Primary parse function ────────────────────────────────

    fun parse(input: String): ParsedMacros? {
        if (input.isBlank()) return null

        val cleaned = input.lowercase()
            .replace("zero", "0").replace("one", "1").replace("two", "2")
            .replace("three", "3").replace("four", "4").replace("five", "5")
            .replace("six", "6").replace("seven", "7").replace("eight", "8")
            .replace("nine", "9").replace("ten", "10").replace("eleven", "11")
            .replace("twelve", "12").replace("thirteen", "13").replace("fourteen", "14")
            .replace("fifteen", "15").replace("sixteen", "16").replace("seventeen", "17")
            .replace("eighteen", "18").replace("nineteen", "19").replace("twenty", "20")
            .replace("thirty", "30").replace("forty", "40").replace("fifty", "50")
            .replace("sixty", "60").replace("seventy", "70").replace("eighty", "80")
            .replace("ninety", "90").replace("hundred", "00")
            .replace("calories", "cal")
            .replace("calories", "cal")
            .replace("calorie", "cal")
            .replace("carbohydrates", "carbs")
            .replace("carbohydrate", "carbs")
            .replace("protein", "prot")
            .replace("net carbs", "carbs")
            .replace("net carb", "carbs")
            .replace("fiber", "fib")
            .replace("fibre", "fib")
            .replace("grams", "")
            .replace("gram", "")
            .replace(" g ", " ")
            .replace("and", "")
            .trim()

        Log.d(TAG, "Parsing cleaned input: $cleaned")

        // Try labeled format first (most reliable)
        val labeled = parseLabeledFormat(cleaned)
        if (labeled != null) {
            Log.d(TAG, "Parsed with labeled format: $labeled")
            return labeled
        }

        // Fall back to positional format (cal/fat/prot/carbs/fiber)
        val positional = parsePositionalFormat(cleaned)
        if (positional != null) {
            Log.d(TAG, "Parsed with positional format: $positional")
            return positional
        }

        Log.w(TAG, "Failed to parse input: $input")
        return null
    }

    // ── Labeled format parser ─────────────────────────────────
    // Handles: "350 cal 28 fat 22 prot 4 carbs 12 fib"

    private fun parseLabeledFormat(input: String): ParsedMacros? {
        val calories  = extractLabeled(input, listOf("cal", "kcal"))
        val fat       = extractLabeled(input, listOf("fat"))
        val protein   = extractLabeled(input, listOf("prot", "pro"))
        val netCarbs  = extractLabeled(input, listOf("carbs", "carb", "net"))
        val fiber     = extractLabeled(input, listOf("fib", "fibre"))

        // Need at least 4 of 5 values for labeled format
        val found = listOf(calories, fat, protein, netCarbs, fiber).count { it != null }
        if (found < 4) return null

        return ParsedMacros(
            calories     = calories?.toInt() ?: estimateCalories(fat, protein, netCarbs, fiber),
            fatGrams     = fat ?: 0.0,
            proteinGrams = protein ?: 0.0,
            netCarbGrams = netCarbs ?: 0.0,
            fiberGrams   = fiber ?: 0.0
        )
    }

    private fun extractLabeled(input: String, labels: List<String>): Double? {
        for (label in labels) {
            // Match: number before label ("28 fat") or after label ("fat 28")
            val beforePattern = Regex("""(\d+\.?\d*)\s*$label""")
            val afterPattern  = Regex("""$label\s*(\d+\.?\d*)""")

            beforePattern.find(input)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                return it
            }
            afterPattern.find(input)?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                return it
            }
        }
        return null
    }

    // ── Positional format parser ──────────────────────────────
    // Handles: "350, 28, 22, 4, 12" or "350 28 22 4 12"

    private fun parsePositionalFormat(input: String): ParsedMacros? {
        val numbers = Regex("""\d+\.?\d*""")
            .findAll(input)
            .map { it.value.toDoubleOrNull() }
            .filterNotNull()
            .toList()

        if (numbers.size < 5) return null

        return ParsedMacros(
            calories     = numbers[0].toInt(),
            fatGrams     = numbers[1],
            proteinGrams = numbers[2],
            netCarbGrams = numbers[3],
            fiberGrams   = numbers[4]
        )
    }

    // ── Calorie estimator (if not spoken) ─────────────────────
    // Fat: 9 cal/g, Protein: 4 cal/g, Carbs: 4 cal/g

    private fun estimateCalories(
        fat: Double?, protein: Double?,
        netCarbs: Double?, fiber: Double?
    ): Int {
        val fatCal     = (fat ?: 0.0) * 9.0
        val protCal    = (protein ?: 0.0) * 4.0
        val carbCal    = (netCarbs ?: 0.0) * 4.0
        return (fatCal + protCal + carbCal).toInt()
    }

    // ── Format for display confirmation ──────────────────────

    fun format(macros: ParsedMacros): String {
        return buildString {
            append("${macros.calories} cal | ")
            append("F: ${macros.fatGrams}g | ")
            append("P: ${macros.proteinGrams}g | ")
            append("C: ${macros.netCarbGrams}g | ")
            append("Fib: ${macros.fiberGrams}g")
            append("\nKeto ratio: ${"%.1f".format(macros.ketogenicRatio)}")
        }
    }

    // ── Test with sample inputs ───────────────────────────────

    fun runSelfTest(): Boolean {
        val tests = listOf(
            "350 calories 28 fat 22 protein 4 net carbs 12 fiber" to
                    ParsedMacros(350, 28.0, 22.0, 4.0, 12.0),
            "350, 28, 22, 4, 12" to
                    ParsedMacros(350, 28.0, 22.0, 4.0, 12.0),
            "28 fat 22 protein 4 carbs 12 fiber 350 cal" to
                    ParsedMacros(350, 28.0, 22.0, 4.0, 12.0)
        )

        var passed = 0
        tests.forEach { (input, expected) ->
            val result = parse(input)
            if (result?.calories == expected.calories &&
                result.fatGrams == expected.fatGrams &&
                result.proteinGrams == expected.proteinGrams &&
                result.netCarbGrams == expected.netCarbGrams &&
                result.fiberGrams == expected.fiberGrams) {
                passed++
                Log.d(TAG, "✓ Test passed: $input")
            } else {
                Log.e(TAG, "✗ Test failed: $input → $result")
            }
        }
        Log.d(TAG, "Self test: $passed/${tests.size} passed")
        return passed == tests.size
    }
}