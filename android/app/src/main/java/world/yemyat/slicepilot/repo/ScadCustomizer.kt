package world.yemyat.slicepilot.repo

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Parses standard OpenSCAD Customizer declarations used by MakerWorld's
 * Parametric Model Maker out of a `.scad` source and applies edited values back:
 *
 * ```
 * /* [Text Settings] */
 * // Name printed on the keychain
 * keychain_text = "Samantha";              // quoted string -> text field
 * font_name = "Lobster"; //font            // font string   -> font text field
 * style = "pill"; // [pill:Rounded, rect:Square] -> dropdown choice
 * letter_spacing = 1.00; // [0.75:0.01:1.30]     -> slider [min:step:max]
 * thickness = 3.2;                         // plain number  -> numeric field
 * ring_at_text_end = false;                // boolean       -> switch
 * /* [Hidden] */
 * $fn = 64;                                // ignored in UI, preserved verbatim
 * ```
 */
object ScadCustomizer {
    data class ChoiceOption(
        val value: String,
        val label: String,
    )

    sealed class Param {
        abstract val name: String
        abstract var value: String
        abstract val section: String

        /** Free text (strings, fonts, colors). [value] stores the SCAD literal (quoted when [isQuoted]). */
        data class Text(
            override val name: String,
            override var value: String,
            val isQuoted: Boolean,
            val isFont: Boolean,
            val isColor: Boolean = false,
            val raw: String = value,
            override val section: String = "",
        ) : Param()

        /** Dropdown enum from `// [Opt1, Opt2]` or `// [val:Label, ...]`. */
        data class Choice(
            override val name: String,
            override var value: String,
            val options: List<ChoiceOption>,
            val isQuoted: Boolean,
            override val section: String = "",
        ) : Param()

        /** Numeric slider from a `[min:step:max]` or `[min:max]` annotation. */
        data class Slider(
            override val name: String,
            override var value: String,
            val min: Double,
            val max: Double,
            val step: Double,
            val isInt: Boolean,
            override val section: String = "",
        ) : Param()

        /** Plain numeric constant without an explicit `[min:max]` slider range. */
        data class Number(
            override val name: String,
            override var value: String,
            val isInt: Boolean,
            override val section: String = "",
        ) : Param()

        data class Bool(
            override val name: String,
            override var value: String,
            override val section: String = "",
        ) : Param() {
            var on: Boolean
                get() = value.equals("true", true)
                set(v) {
                    value = if (v) "true" else "false"
                }
        }

        /** Vectors, formulas, or unsupported expressions: kept verbatim, hidden from the form. */
        data class Raw(
            override val name: String,
            override var value: String,
            override val section: String = "",
        ) : Param()
    }

    private val SECTION = Regex("""^\s*/\*\s*\[([^\]]+)\]\s*\*/\s*$""")
    private val DECL = Regex("""^([A-Za-z_$][\w$]*)\s*=\s*(.+?);(.*)$""")
    private val RANGE = Regex("""\[\s*(-?[\d.]+)\s*:\s*(-?[\d.]+)(?:\s*:\s*(-?[\d.]+))?\s*\]""")
    private val BRACKET_LIST = Regex("""\[([^\]]+)\]""")
    private val HEX_COLOR = Regex("""^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$""")
    private val MODULE_OR_FN = Regex("""^\s*(?:module|function)\s+[A-Za-z_$]""")

    /**
     * Parses top-level OpenSCAD customizer parameters from [scad].
     *
     * Follows the OpenSCAD Customizer specification:
     * - Tracks `/* [Section] */` tabs and skips `/* [Hidden] */` (case-insensitive)
     * - Stops at the first `module` or `function` definition
     * - Ignores indented declarations and declarations inside `{ ... }` blocks
     */
    fun parse(scad: String): List<Param> {
        val out = ArrayList<Param>()
        var currentSection = ""
        var hidden = false
        var braceDepth = 0
        var inBlockComment = false

        for (rawLine in scad.lines()) {
            val trimmed = rawLine.trim()

            // Check single-line section headers like `/* [Text Settings] */` before comment tracking.
            val secMatch = SECTION.matchEntire(trimmed)
            if (secMatch != null) {
                val secName = secMatch.groupValues[1].trim()
                hidden = secName.equals("Hidden", ignoreCase = true)
                currentSection = if (hidden) "" else secName
                continue
            }

            // Track multi-line /* ... */ block comments.
            if (inBlockComment) {
                if (trimmed.contains("*/")) inBlockComment = false
                continue
            }
            if (trimmed.startsWith("/*") && !trimmed.contains("*/")) {
                inBlockComment = true
                continue
            }

            // OpenSCAD Customizer stops scanning parameters at the first module/function.
            if (braceDepth == 0 && MODULE_OR_FN.containsMatchIn(rawLine)) {
                break
            }

            // Track brace depth conservatively (ignoring strings/comments on simple lines).
            val codeBeforeComment = stripLineComment(rawLine)
            val prevDepth = braceDepth
            braceDepth += codeBeforeComment.count { it == '{' } - codeBeforeComment.count { it == '}' }
            if (braceDepth < 0) braceDepth = 0
            if (prevDepth > 0 || hidden) continue

            if (rawLine.isNotEmpty() && rawLine[0].isWhitespace()) continue
            val m = DECL.matchEntire(rawLine.trimEnd()) ?: continue
            val (name, expr, trailing) = m.destructured

            // Special OpenSCAD resolution variables ($fn, $fa, $fs) are internal unless annotated.
            val comment = trailing.trim()
            if (name.startsWith("$") && !comment.contains("//")) continue

            val raw = expr.trim()
            val inlineComment =
                comment.indexOf("//").takeIf { it >= 0 }?.let { comment.substring(it) }.orEmpty()

            val param = classifyParam(name, raw, inlineComment, currentSection)
            if (param != null) out.add(param)
        }
        return out
    }

    private fun classifyParam(
        name: String,
        raw: String,
        inlineComment: String,
        section: String,
    ): Param? {
        val num = raw.toDoubleOrNull()
        val range = RANGE.find(inlineComment)
        if (range != null && num != null) {
            val a = range.groupValues[1].toDouble()
            val hasStep = range.groupValues[3].isNotEmpty()
            val step = if (hasStep) range.groupValues[2].toDouble() else 1.0
            val b = (if (hasStep) range.groupValues[3] else range.groupValues[2]).toDouble()
            val min = minOf(a, b)
            val max = maxOf(a, b)
            val isInt = isWhole(min) && isWhole(max) && isWhole(step) && isWhole(num)
            return Param.Slider(
                name = name,
                value = raw,
                min = min,
                max = max,
                step = abs(step).coerceAtLeast(if (isInt) 1.0 else 0.01),
                isInt = isInt,
                section = section,
            )
        }

        // Dropdown / combo box: `// [Option1, Option2]` or `// [0:Small, 1:Medium]`.
        val bracket = BRACKET_LIST.find(inlineComment)
        if (bracket != null && range == null) {
            val inner = bracket.groupValues[1].trim()
            if (inner.contains(',') || inner.contains(':')) {
                val isQuoted = raw.startsWith("\"") && raw.endsWith("\"")
                val options = parseChoiceOptions(inner, isQuoted)
                if (options.size >= 2) {
                    return Param.Choice(
                        name = name,
                        value = raw,
                        options = options,
                        isQuoted = isQuoted,
                        section = section,
                    )
                }
            }
        }

        if (raw == "true" || raw == "false") {
            return Param.Bool(name = name, value = raw, section = section)
        }

        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
            val unquoted = unquoteScadString(raw)
            val lowerComment = inlineComment.lowercase(Locale.US)
            val lowerName = name.lowercase(Locale.US)
            val isColor =
                lowerComment.contains("//color") ||
                    lowerComment.contains("// color") ||
                    HEX_COLOR.matches(unquoted)
            val nameLooksLikeFont =
                lowerName.contains("font") &&
                    !lowerName.contains("size") &&
                    !lowerName.contains("color") &&
                    !lowerName.contains("colour") &&
                    !lowerName.contains("height") &&
                    !lowerName.contains("weight") &&
                    !lowerName.contains("spacing") &&
                    !lowerName.contains("scale") &&
                    !lowerName.contains("offset")
            val isFont =
                !isColor &&
                    (lowerComment.contains("//font") || lowerComment.contains("// font") || nameLooksLikeFont)
            return Param.Text(
                name = name,
                value = raw,
                isQuoted = true,
                isFont = isFont,
                isColor = isColor,
                raw = raw,
                section = section,
            )
        }

        if (num != null) {
            return Param.Number(
                name = name,
                value = raw,
                isInt = isWhole(num) && !raw.contains('.'),
                section = section,
            )
        }

        // Vectors, formulas, or function calls: keep in Param.Raw so apply() doesn't break them.
        return Param.Raw(name = name, value = raw, section = section)
    }

    private fun parseChoiceOptions(
        inner: String,
        isQuoted: Boolean,
    ): List<ChoiceOption> {
        val parts = inner.split(',')
        val out = ArrayList<ChoiceOption>(parts.size)
        for (part in parts) {
            val item = part.trim()
            if (item.isEmpty()) continue
            val colon = item.indexOf(':')
            val rawVal: String
            val label: String
            if (colon > 0) {
                rawVal = item.substring(0, colon).trim().removeSurrounding("\"")
                label = item.substring(colon + 1).trim().removeSurrounding("\"")
            } else {
                rawVal = item.removeSurrounding("\"")
                label = rawVal
            }
            val scadLiteral = if (isQuoted) quoteScadString(rawVal) else rawVal
            out.add(ChoiceOption(value = scadLiteral, label = label.ifBlank { rawVal }))
        }
        return out
    }

    /** Rewrites the `name = <value>;` lines in [scad] with the (edited) [params]. */
    fun apply(
        scad: String,
        params: List<Param>,
    ): String {
        val byName = params.associateBy { it.name }
        var braceDepth = 0
        var stopped = false
        return scad.lines().joinToString("\n") { line ->
            if (stopped) return@joinToString line
            if (braceDepth == 0 && MODULE_OR_FN.containsMatchIn(line)) {
                stopped = true
                return@joinToString line
            }
            val codeBeforeComment = stripLineComment(line)
            val prevDepth = braceDepth
            braceDepth += codeBeforeComment.count { it == '{' } - codeBeforeComment.count { it == '}' }
            if (braceDepth < 0) braceDepth = 0
            if (prevDepth > 0 || line.isEmpty() || line[0].isWhitespace()) {
                line
            } else {
                val m = DECL.matchEntire(line.trimEnd()) ?: return@joinToString line
                val p = byName[m.destructured.component1()] ?: return@joinToString line
                if (p is Param.Raw) return@joinToString line
                val suffix = m.groupValues.getOrNull(3)?.trim().orEmpty()
                "${p.name} = ${p.value};${if (suffix.isNotEmpty()) " $suffix" else ""}"
            }
        }
    }

    /** Display value of a param (strings unquoted and unescaped). */
    fun displayValue(p: Param): String =
        when (p) {
            is Param.Text -> if (p.isQuoted) unquoteScadString(p.value) else p.value
            is Param.Choice -> if (p.isQuoted) unquoteScadString(p.value) else p.value
            is Param.Slider -> p.value
            is Param.Number -> p.value
            is Param.Bool -> p.value
            is Param.Raw -> p.value
        }

    /** Formats a user-entered string into a valid OpenSCAD quoted string literal. */
    fun quoteScadString(text: String): String {
        val sb = StringBuilder(text.length + 2)
        sb.append('"')
        for (ch in text) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> {}
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** Unquotes and unescapes an OpenSCAD string literal for editing/display. */
    fun unquoteScadString(literal: String): String {
        val inner =
            if (literal.length >= 2 && literal.startsWith("\"") && literal.endsWith("\"")) {
                literal.substring(1, literal.length - 1)
            } else {
                literal
            }
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val ch = inner[i]
            if (ch == '\\' && i + 1 < inner.length) {
                when (val next = inner[i + 1]) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    /** Formats a slider value snapped to [step] without IEEE-754 trailing drift (e.g. `1.1500000000000001`). */
    fun formatSliderValue(
        value: Double,
        step: Double,
        isInt: Boolean,
    ): String {
        if (isInt) return value.roundToLong().toString()
        val safeStep = if (step > 0.0) step else 0.01
        val snapped = (value / safeStep).roundToLong() * safeStep
        val decimals = decimalPlaces(safeStep).coerceIn(1, 4)
        val factor = 10.0.pow(decimals)
        val rounded = (snapped * factor).roundToLong() / factor
        return String.format(Locale.US, "%.${decimals}f", rounded)
            .trimEnd('0')
            .trimEnd('.')
            .ifEmpty { "0" }
    }

    /** Human label: nearest preceding `// comment` above the declaration, or a humanized variable name. */
    fun label(
        scad: String,
        name: String,
    ): String {
        val lines = scad.lines()
        val declPrefix = Regex("""^${Regex.escape(name)}\s*=""")
        val idx =
            lines.indexOfFirst {
                it.isNotEmpty() && !it[0].isWhitespace() && declPrefix.containsMatchIn(it)
            }
        if (idx > 0) {
            var i = idx - 1
            while (i >= 0 && lines[i].trim().startsWith("//")) {
                val t = lines[i].trim().removePrefix("//").trim()
                if (t.isNotEmpty() && !t.startsWith("[")) return t
                i--
            }
        }
        return humanizeName(name)
    }

    /** Converts `keychain_text` or `fontSize` into `"Keychain text"` / `"Font size"`. */
    fun humanizeName(name: String): String {
        val spaced =
            name
                .trimStart('$', '_')
                .replace('_', ' ')
                .replace(Regex("""([a-z0-9])([A-Z])""")) { m ->
                    "${m.groupValues[1]} ${m.groupValues[2].lowercase(Locale.US)}"
                }.replace(Regex("""\s+"""), " ")
                .trim()
        if (spaced.isEmpty()) return name
        return spaced.replaceFirstChar { it.titlecase(Locale.US) }
    }

    private fun stripLineComment(line: String): String {
        var inQuotes = false
        var escaped = false
        for (i in line.indices) {
            val c = line[i]
            if (inQuotes) {
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inQuotes = false
                }
            } else {
                if (c == '"') {
                    inQuotes = true
                } else if (c == '/' && i + 1 < line.length && line[i + 1] == '/') {
                    return line.substring(0, i)
                }
            }
        }
        return line
    }

    private fun isWhole(v: Double): Boolean = abs(v - v.roundToLong().toDouble()) < 1e-9

    private fun decimalPlaces(step: Double): Int {
        val s = String.format(Locale.US, "%.6f", step).trimEnd('0')
        val dot = s.indexOf('.')
        return if (dot < 0 || dot == s.lastIndex) 0 else s.length - dot - 1
    }
}
