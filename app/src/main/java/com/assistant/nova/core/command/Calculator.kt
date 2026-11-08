package com.assistant.nova.core.command

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Spoken arithmetic, fully offline: "25 times 4", "15 percent of 2000", "square root of 144",
 * "2 to the power of 10", "1 lakh divided by 12". Input is canonical text (digits, lowercase).
 */
object Calculator {

    private val MULTIPLIERS = mapOf(
        "hundred" to 1e2, "thousand" to 1e3, "lakh" to 1e5, "lakhs" to 1e5, "lac" to 1e5,
        "crore" to 1e7, "crores" to 1e7, "million" to 1e6, "billion" to 1e9,
    )

    private val PHRASES = listOf(
        "multiplied by" to " * ", "divided by" to " / ", "to the power of" to " ^ ", "raised to" to " ^ ",
        "square root of" to " sqrt ", "root of" to " sqrt ", "percent of" to " %of ", "out of" to " / ",
        "times" to " * ", "into" to " * ", "x" to " * ", "multiply" to " * ", "over" to " / ", "by" to " / ",
        "plus" to " + ", "add" to " + ", "minus" to " - ", "subtract" to " - ", "less" to " - ",
        "power" to " ^ ", "squared" to " ^ 2 ", "cubed" to " ^ 3 ", "percent" to " % ",
        "open bracket" to " ( ", "close bracket" to " ) ",
    )

    /** Returns the result, or null if [text] isn't a calculation. */
    fun evaluate(text: String): Double? {
        val tokens = tokenize(text) ?: return null
        if (tokens.none { it in setOf("+", "-", "*", "/", "^", "%of", "%", "sqrt") }) return null
        if (tokens.none { it.toDoubleOrNull() != null }) return null
        return try {
            val p = Parser(tokens)
            val v = p.expr()
            if (p.pos != tokens.size || v.isNaN() || v.isInfinite()) null else v
        } catch (_: Exception) {
            null
        }
    }

    private fun tokenize(text: String): List<String>? {
        var s = " ${text.lowercase(Locale.ROOT)} "
        s = s.replace(Regex("(\\d)\\s+point\\s+(\\d)"), "$1.$2")
        for ((w, op) in PHRASES) s = s.replace(Regex("(?<=\\s)${Regex.escape(w)}(?=\\s)"), op)
        s = s.replace("(", " ( ").replace(")", " ) ").replace("+", " + ").replace("*", " * ")
        val out = ArrayList<String>()
        for (raw in s.trim().split(Regex("\\s+"))) {
            if (raw.isEmpty() || raw == "the" || raw == "and" || raw == "of") continue
            val mult = MULTIPLIERS[raw]
            when {
                mult != null -> {
                    val prev = out.lastOrNull()?.toDoubleOrNull() ?: return null
                    out[out.size - 1] = (prev * mult).toString()
                }
                raw.toDoubleOrNull() != null -> {
                    // "2 thousand 500" -> 2500
                    val prev = out.lastOrNull()?.toDoubleOrNull()
                    if (prev != null && prev >= 100 && prev % 100 == 0.0 && raw.toDouble() < prev) {
                        out[out.size - 1] = (prev + raw.toDouble()).toString()
                    } else {
                        out += raw
                    }
                }
                raw in setOf("+", "-", "*", "/", "^", "%of", "%", "sqrt", "(", ")") -> out += raw
                else -> return null // any other word: not a calculation
            }
        }
        return out
    }

    private class Parser(val t: List<String>) {
        var pos = 0
        fun peek() = t.getOrNull(pos)
        fun next() = t[pos++]

        fun expr(): Double {
            var v = term()
            while (peek() == "+" || peek() == "-") v = if (next() == "+") v + term() else v - term()
            return v
        }

        fun term(): Double {
            var v = power()
            while (peek() == "*" || peek() == "/" || peek() == "%of") {
                v = when (next()) {
                    "*" -> v * power()
                    "/" -> v / power()
                    else -> v / 100.0 * power()
                }
            }
            return v
        }

        fun power(): Double {
            val base = unary()
            return if (peek() == "^") { next(); base.pow(power()) } else base
        }

        fun unary(): Double = when (peek()) {
            "-" -> { next(); -unary() }
            "sqrt" -> { next(); sqrt(unary()) }
            else -> {
                var v = primary()
                if (peek() == "%") { next(); v /= 100.0 }
                v
            }
        }

        fun primary(): Double {
            val tok = next()
            if (tok == "(") {
                val v = expr()
                if (next() != ")") error("missing )")
                return v
            }
            return tok.toDouble()
        }
    }

    /** 100.0 -> "100", 3.333333 -> "3.33", 1234567.0 -> "1,234,567" */
    fun format(v: Double): String {
        val r = (v * 100).roundToLong() / 100.0
        return if (abs(r - r.roundToLong()) < 1e-9) String.format(Locale.ENGLISH, "%,d", r.roundToLong())
        else String.format(Locale.ENGLISH, "%,.2f", r).trimEnd('0').trimEnd('.')
    }
}

/** Unit conversion, offline: length, weight, volume and temperature. */
object Units {
    enum class Kind { LENGTH, WEIGHT, VOLUME, TEMPERATURE }
    data class Unit(val name: String, val kind: Kind, val toBase: Double)

    private val LIST = listOf(
        Unit("millimetres", Kind.LENGTH, 0.001), Unit("centimetres", Kind.LENGTH, 0.01), Unit("metres", Kind.LENGTH, 1.0),
        Unit("kilometres", Kind.LENGTH, 1000.0), Unit("inches", Kind.LENGTH, 0.0254), Unit("feet", Kind.LENGTH, 0.3048),
        Unit("yards", Kind.LENGTH, 0.9144), Unit("miles", Kind.LENGTH, 1609.344),
        Unit("grams", Kind.WEIGHT, 0.001), Unit("kilograms", Kind.WEIGHT, 1.0), Unit("pounds", Kind.WEIGHT, 0.45359237),
        Unit("ounces", Kind.WEIGHT, 0.028349523125), Unit("tonnes", Kind.WEIGHT, 1000.0),
        Unit("millilitres", Kind.VOLUME, 0.001), Unit("litres", Kind.VOLUME, 1.0), Unit("gallons", Kind.VOLUME, 3.785411784),
        Unit("celsius", Kind.TEMPERATURE, 1.0), Unit("fahrenheit", Kind.TEMPERATURE, 1.0), Unit("kelvin", Kind.TEMPERATURE, 1.0),
    ).associateBy { it.name }

    private val ALIASES = mapOf(
        "mm" to "millimetres", "millimeter" to "millimetres", "millimeters" to "millimetres", "millimetre" to "millimetres",
        "cm" to "centimetres", "centimeter" to "centimetres", "centimeters" to "centimetres", "centimetre" to "centimetres",
        "m" to "metres", "meter" to "metres", "meters" to "metres", "metre" to "metres",
        "km" to "kilometres", "kms" to "kilometres", "kilometer" to "kilometres", "kilometers" to "kilometres", "kilometre" to "kilometres",
        "inch" to "inches", "in" to "inches", "foot" to "feet", "ft" to "feet", "yard" to "yards", "mile" to "miles",
        "g" to "grams", "gram" to "grams", "kg" to "kilograms", "kgs" to "kilograms", "kilo" to "kilograms", "kilos" to "kilograms",
        "kilogram" to "kilograms", "pound" to "pounds", "lb" to "pounds", "lbs" to "pounds", "ounce" to "ounces", "oz" to "ounces",
        "ton" to "tonnes", "tons" to "tonnes", "tonne" to "tonnes",
        "ml" to "millilitres", "milliliter" to "millilitres", "milliliters" to "millilitres", "millilitre" to "millilitres",
        "l" to "litres", "liter" to "litres", "liters" to "litres", "litre" to "litres", "gallon" to "gallons",
        "c" to "celsius", "centigrade" to "celsius", "f" to "fahrenheit", "k" to "kelvin",
    )

    fun unit(word: String): Unit? = LIST[word] ?: ALIASES[word]?.let { LIST[it] }

    fun convert(value: Double, from: Unit, to: Unit): Double? {
        if (from.kind != to.kind) return null
        if (from.kind != Kind.TEMPERATURE) return value * from.toBase / to.toBase
        val c = when (from.name) {
            "fahrenheit" -> (value - 32) * 5 / 9
            "kelvin" -> value - 273.15
            else -> value
        }
        return when (to.name) {
            "fahrenheit" -> c * 9 / 5 + 32
            "kelvin" -> c + 273.15
            else -> c
        }
    }
}
