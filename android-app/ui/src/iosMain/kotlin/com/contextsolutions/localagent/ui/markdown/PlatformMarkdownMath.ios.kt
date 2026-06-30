package com.contextsolutions.localagent.ui.markdown

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.m3.Markdown

/**
 * iOS [PlatformMarkdownMath] (PR #41) — the mikepenz Compose-Multiplatform
 * markdown renderer (no WebView). LaTeX is **not** rendered to images on iOS this
 * milestone (JLaTeXMath is JVM-only); any math is left as literal text. Wrapped in
 * a [SelectionContainer] so LLM answers can be selected + copied, matching the
 * desktop actual.
 */
@Composable
actual fun PlatformMarkdownMath(text: String, modifier: Modifier) {
    SelectionContainer(modifier) {
        Markdown(content = text)
    }
}
