package dev.phonebot.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.net.URLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AnswerResources(private val context: Context, private val botId: String) {
    suspend fun imageSource(reference: String): Any? = withContext(Dispatchers.IO) {
        try {
            val uri = reference.toUri()
            if (uri.scheme == "https" && !uri.host.isNullOrBlank()) reference else localFile(uri)
        } catch (_: IOException) { null }
    }

    suspend fun open(reference: String) {
        val intent = withContext(Dispatchers.IO) {
            try {
                val uri = reference.toUri()
                if (uri.scheme == "https" && !uri.host.isNullOrBlank()) Intent(Intent.ACTION_VIEW, uri)
                else localFile(uri)?.let { file ->
                    val type = file.inputStream().buffered().use(URLConnection::guessContentTypeFromStream) ?: "application/octet-stream"
                    Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.answers", file), type)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } catch (_: IOException) { null }
        }
        withContext(Dispatchers.Main.immediate) {
            if (intent == null) Toast.makeText(context, "链接不可用", Toast.LENGTH_SHORT).show()
            else try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show()
            } catch (_: SecurityException) {
                Toast.makeText(context, "无法打开此链接", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun localFile(uri: Uri): File? {
        if (uri.scheme != "phonebot" || uri.authority != "images" || uri.query != null || uri.fragment != null) return null
        if (!botId.matches(Regex("[a-zA-Z0-9-]{1,80}"))) return null
        val parts = uri.pathSegments
        if (parts.size != 2 || parts[0] != botId || !parts[1].matches(Regex("[a-f0-9]{64}\\.source"))) return null
        val directory = File(context.filesDir.canonicalFile, "images/$botId")
        val file = File(directory, parts[1])
        return file.takeIf { directory.canonicalFile == directory.absoluteFile && it.canonicalFile == it.absoluteFile && it.isFile }
    }
}
