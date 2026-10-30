package tech.devline.scropy_ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal enum class UiLanguage(val code: String, val label: String) {
    CHINESE("zh", "中文"), ENGLISH("en", "English"), JAPANESE("ja", "日本語");
    companion object {
        fun from(code: String?) = entries.firstOrNull { it.code == code } ?: JAPANESE
    }
}

internal object UiLanguageState {
    var current by mutableStateOf(UiLanguage.JAPANESE)
        private set

    fun load(context: Context) {
        current = UiLanguage.from(context.getSharedPreferences("aura_language", Context.MODE_PRIVATE).getString("language", null))
    }

    fun select(context: Context, language: UiLanguage) {
        context.getSharedPreferences("aura_language", Context.MODE_PRIVATE).edit().putString("language", language.code).apply()
        current = language
    }
}

private val placeholder = Regex("\\{(\\d+)\\}")
private data class CompiledUiPattern(val pattern: UiPattern, val regex: Regex, val indexes: List<Int>)
private val compiledUiPatterns = uiPatterns.map { item ->
    CompiledUiPattern(item, Regex("^" + item.ja.split(placeholder).joinToString("([\\s\\S]*?)") { Regex.escape(it) } + "$"),
        placeholder.findAll(item.ja).map { it.groupValues[1].toInt() }.toList())
}

/** Presentation only: command strings, package IDs, stored URLs and device identity stay untouched. */
internal fun translateUi(source: String, language: UiLanguage, depth: Int = 0): String {
    if (language == UiLanguage.JAPANESE || source.isBlank() || depth > 5) return source
    uiMessages[source]?.let { return it[if (language == UiLanguage.CHINESE) 0 else 1] }
    for (compiled in compiledUiPatterns) {
        val match = compiled.regex.matchEntire(source) ?: continue
        val target = if (language == UiLanguage.CHINESE) compiled.pattern.zh else compiled.pattern.en
        return placeholder.replace(target) { token ->
            val group = compiled.indexes.indexOf(token.groupValues[1].toInt()) + 1
            if (group == 0) token.value else translateUi(match.groupValues[group], language, depth + 1)
        }
    }
    if ('\n' in source) return source.split('\n').joinToString("\n") { translateUi(it, language, depth + 1) }
    val trimmed = source.trim()
    if (trimmed != source) return source.takeWhile { it.isWhitespace() } + translateUi(trimmed, language, depth + 1) + source.takeLastWhile { it.isWhitespace() }
    return source
}

internal fun uiText(source: String): String = translateUi(source, UiLanguageState.current)
