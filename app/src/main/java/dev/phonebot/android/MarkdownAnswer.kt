package dev.phonebot.android

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

internal object MarkdownAnswer {
    fun plain(source: String): String {
        val parser = MarkdownParser(GFMFlavourDescriptor())
        val text = StringBuilder()
        fun visit(node: ASTNode) {
            if (node.type in setOf(MarkdownTokenTypes.TEXT, MarkdownTokenTypes.CODE_LINE, MarkdownTokenTypes.CODE_FENCE_CONTENT)) text.append(source.substring(node.startOffset, node.endOffset))
            else if (node.type == MarkdownTokenTypes.EOL) text.append(' ')
            else node.children.forEach(::visit)
        }
        visit(parser.buildMarkdownTreeFromString(source as CharSequence))
        return text.toString().trim()
    }

    @Composable fun Content(text: String, streaming: Boolean = false) {
        val stream = rememberStreamingMarkdownState()
        LaunchedEffect(text, streaming) {
            if (streaming && text.startsWith(stream.content.toString())) withContext(Dispatchers.Default) {
                stream.append(text.substring(stream.content.length))
            }
        }
        val colors = markdownColor(text = MaterialTheme.colorScheme.onSurface, codeBackground = Color(0xFF222222), inlineCodeBackground = Color(0xFF2A2A2A), tableBackground = Color(0xFF1D1D1D))
        val body = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 28.sp)
        val typography = markdownTypography(text = body, paragraph = body, ordered = body, bullet = body, list = body,
            h1 = body.copy(fontSize = 23.sp, fontWeight = FontWeight.SemiBold), h2 = body.copy(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
            h3 = body.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold), code = body.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            inlineCode = body.copy(fontFamily = FontFamily.Monospace), table = body)
        val highlighter = remember { Highlights.Builder().theme(SyntaxThemes.default(darkMode = true)) }
        val components = markdownComponents(
            codeFence = { MarkdownHighlightedCodeFence(it.content, it.node, highlightsBuilder = highlighter, showHeader = true) },
            codeBlock = { MarkdownHighlightedCodeBlock(it.content, it.node, highlightsBuilder = highlighter, showHeader = true) })
        SelectionContainer {
            if (streaming) Markdown(stream, colors = colors, typography = typography, components = components, modifier = Modifier.fillMaxWidth())
            else {
                val state = rememberMarkdownState(text, retainState = true)
                Markdown(state, colors = colors, typography = typography, components = components, modifier = Modifier.fillMaxWidth(),
                    loading = { Markdown(stream, colors = colors, typography = typography, components = components, modifier = Modifier.fillMaxWidth()) })
            }
        }
    }
}
