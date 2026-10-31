package dev.phonebot.android.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import java.io.File
import org.json.JSONObject

internal object ModelImages {
    fun encode(bitmap: Bitmap, file: File): JSONObject {
        val factor = minOf(1.0, 1280.0 / maxOf(bitmap.width, bitmap.height))
        val resized = if (factor < 1) bitmap.scale(maxOf(1, (bitmap.width * factor).toInt()), maxOf(1, (bitmap.height * factor).toInt())) else bitmap
        try {
            file.parentFile?.mkdirs()
            file.outputStream().use { check(resized.compress(Bitmap.CompressFormat.JPEG, 80, it)) }
            return JSONObject().put("image", file.path).put("mimeType", "image/jpeg").put("width", resized.width).put("height", resized.height)
        } finally { if (resized !== bitmap) resized.recycle() }
    }

    fun prepare(directory: File, reference: String): JSONObject {
        require(reference.matches(Regex("[a-f0-9]{64}"))) { "图片引用无效" }
        val source = File(directory, "$reference.source")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "图片不可读取" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2560) sample *= 2
        val bitmap = checkNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }))
        return try { encode(bitmap, File(directory, "$reference.jpg")) } finally { bitmap.recycle() }
    }
}
