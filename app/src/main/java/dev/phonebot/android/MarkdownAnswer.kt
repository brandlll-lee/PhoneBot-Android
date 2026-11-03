package dev.phonebot.android

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.rememberConstraintsSizeResolver
import coil3.request.ImageRequest
import coil3.size.SizeResolver
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.LocalReferenceLinkHandler
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.utils.resolveImageAlt
import com.mikepenz.markdown.utils.resolveImageLink
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    @Composable fun Content(text: String, streaming: Boolean = false, color: Color = MaterialTheme.colorScheme.onSurface, state: State? = null, botId: String = "") {
        val context = LocalContext.current
        val resources = remember(context, botId) { AnswerResources(context, botId) }
        val scope = rememberCoroutineScope()
        val uriHandler = remember(resources, scope) { object : UriHandler {
            override fun openUri(uri: String) { scope.launch { resources.open(uri) } }
        } }
        val stream = rememberStreamingMarkdownState()
        LaunchedEffect(text, streaming) {
            if (streaming && text.startsWith(stream.content.toString())) withContext(Dispatchers.Default) {
                stream.append(text.substring(stream.content.length))
            }
        }
        val scheme = MaterialTheme.colorScheme
        val colors = markdownColor(text = color, codeBackground = scheme.surfaceContainer, inlineCodeBackground = scheme.surfaceContainerHighest, tableBackground = scheme.surfaceContainerLow)
        val body = PhoneUi.answerBody
        val typography = markdownTypography(text = body, paragraph = body, ordered = body, bullet = body, list = body,
            h1 = PhoneUi.answerHeading1, h2 = PhoneUi.answerHeading2,
            h3 = PhoneUi.answerHeading3, code = PhoneUi.code,
            inlineCode = body.copy(fontFamily = FontFamily.Monospace), table = body)
        val dark = scheme.background == Color.Black
        val highlighter = remember(dark) { Highlights.Builder().theme(SyntaxThemes.default(darkMode = dark)) }
        CompositionLocalProvider(LocalUriHandler provides uriHandler) {
            val size = rememberConstraintsSizeResolver()
            val transformer = remember(resources, size) { AnswerImageTransformer(resources, size) }
            var viewing by remember(botId) { mutableStateOf<Pair<String, String>?>(null) }
            val components = markdownComponents(
                image = { model ->
                    val link = model.node.resolveImageLink(model.content, LocalReferenceLinkHandler.current)
                    if (link != null) AnswerImage(link, model.node.resolveImageAlt(model.content).orEmpty(), transformer) { viewing = link to it }
                },
                inlineImage = { model -> AnswerImage(model.content, model.node.resolveImageAlt(text).orEmpty(), transformer) { viewing = model.content to it } },
                codeFence = { MarkdownHighlightedCodeFence(it.content, it.node, highlightsBuilder = highlighter, showHeader = true) },
                codeBlock = { MarkdownHighlightedCodeBlock(it.content, it.node, highlightsBuilder = highlighter, showHeader = true) })
            SelectionContainer(Modifier.then(size)) {
                @Composable fun renderStream() {
                    Markdown(stream, colors = colors, typography = typography, components = components, imageTransformer = transformer, modifier = Modifier.fillMaxWidth())
                }
                if (streaming) renderStream()
                else if (state != null) Markdown(state, colors = colors, typography = typography, components = components, imageTransformer = transformer, modifier = Modifier.fillMaxWidth())
                else {
                    val parsed = rememberMarkdownState(text, retainState = true)
                    Markdown(parsed, colors = colors, typography = typography, components = components, imageTransformer = transformer, modifier = Modifier.fillMaxWidth(), loading = { renderStream() })
                }
            }
            viewing?.let { (link, description) ->
                Dialog(onDismissRequest = { viewing = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    androidx.compose.material3.Surface(Modifier.fillMaxSize().testTag("answer_image_viewer")) {
                        Box {
                            val viewerSize = rememberConstraintsSizeResolver()
                            val viewer = remember(resources, viewerSize) { AnswerImageTransformer(resources, viewerSize) }
                            AnswerImage(link, description, viewer, Modifier.fillMaxSize().then(viewerSize)) {}
                            TextButton(onClick = { viewing = null }, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()) { Text("关闭图片") }
                        }
                    }
                }
            }
        }
    }
}

private class AnswerImageTransformer(private val resources: AnswerResources, private val size: SizeResolver) : ImageTransformer by Coil3ImageTransformerImpl {
    @Composable override fun transform(link: String): ImageData? = key(resources, link) {
        val resolved by produceState<Pair<Boolean, Any?>>(false to null, resources, link) {
            value = true to withContext(Dispatchers.IO) { resources.imageSource(link) }
        }
        val source = resolved.second
        val context = LocalContext.current
        val request = remember(context, source, size) {
            ImageRequest.Builder(context).data(source).size(size).build()
        }
        val painter = rememberAsyncImagePainter(request)
        if (resolved.first) ImageData(painter) else null
    }
}

@Composable private fun AnswerImage(link: String, description: String, transformer: AnswerImageTransformer,
    modifier: Modifier = Modifier, view: (String) -> Unit) {
    val image = transformer.transform(link)
    val state = (image?.painter as? AsyncImagePainter)?.state?.collectAsState()?.value
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when (state) {
            is AsyncImagePainter.State.Success -> Image(checkNotNull(image).painter, description.ifBlank { "图片" },
                Modifier.fillMaxWidth().testTag("answer_image").clickable(role = Role.Button, onClickLabel = "查看图片") { view(description) })
            is AsyncImagePainter.State.Error -> Text(description.ifBlank { "图片" } + "：无法加载图片", style = PhoneUi.hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(PhoneUi.inset).testTag("answer_image_error"))
            else -> CircularProgressIndicator(Modifier.padding(PhoneUi.inset).size(24.dp).testTag("answer_image_loading"), strokeWidth = 2.dp)
        }
    }
}
