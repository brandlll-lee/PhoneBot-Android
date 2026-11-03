package dev.phonebot.android

import android.content.ContextWrapper
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AnswerResourcesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val bot = "answer-resources-fixture"
    private val hash = "c".repeat(64)
    private val reference = "phonebot://images/$bot/$hash.source"

    @Test fun localReferencesStayWithinTheirBotAndSurviveRecreation() = runBlocking {
        val directory = File(context.filesDir, "images/$bot").apply { mkdirs() }
        val file = File(directory, "$hash.source").apply { writeBytes(byteArrayOf(1, 2)) }
        try {
            assertEquals(file.canonicalFile, AnswerResources(context, bot).imageSource(reference))
            assertEquals(file.canonicalFile, AnswerResources(context, bot).imageSource(reference))
            assertNull(AnswerResources(context, "other-bot").imageSource(reference))
            val resources = AnswerResources(context, bot)
            for (uri in listOf("file://${context.filesDir}/provider-auth.json", "content://private/file", "javascript:alert(1)",
                "http://example.com/image.png", "phonebot://images/$bot/../provider-auth.json", "$reference?file=secret")) {
                assertNull(resources.imageSource(uri))
            }
            assertEquals("https://example.com/image.png", resources.imageSource("https://example.com/image.png"))
            file.delete()
            assertNull(resources.imageSource(reference))
        } finally { directory.deleteRecursively() }
    }

    @Test fun symlinksCannotExposeFilesOutsideTheBot() = runBlocking {
        val directory = File(context.filesDir, "images/$bot").apply { mkdirs() }
        val outside = File(context.cacheDir, "answer-resource-source").apply { writeText("private") }
        val link = File(directory, "$hash.source")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertNull(AnswerResources(context, bot).imageSource(reference))
        } finally { link.delete(); directory.delete(); outside.delete() }
    }

    @Test fun openingUsesFileProviderAndTemporaryReadPermission() = runBlocking {
        var opened: Intent? = null
        val capture = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { opened = intent }
        }
        val directory = File(context.filesDir, "images/$bot").apply { mkdirs() }
        val file = File(directory, "$hash.source")
        try {
            file.writeBytes(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            AnswerResources(capture, bot).open(reference)
            val intent = checkNotNull(opened)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("content", intent.data?.scheme)
            assertEquals("${context.packageName}.answers", intent.data?.authority)
            assertEquals("image/png", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertTrue(context.contentResolver.openInputStream(checkNotNull(intent.data))!!.use { it.readBytes().contentEquals(file.readBytes()) })
        } finally { directory.deleteRecursively() }
    }
}
