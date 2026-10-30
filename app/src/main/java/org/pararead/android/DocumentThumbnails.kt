package org.pararead.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** Small first-page previews, never whole-document bitmaps. Missing or encrypted PDFs use an icon. */
internal object DocumentThumbnails {
    private val slots = Semaphore(1)
    private val memory = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private fun file(context: Context, id: String) = File(File(context.cacheDir, "document-thumbnails"), "$id-v1.png")
    suspend fun load(context: Context, document: DocumentRecord): Bitmap? = withContext(Dispatchers.IO) {
        memory.get(document.id)?.let { return@withContext it }
        slots.withPermit {
            memory.get(document.id)?.let { return@withPermit it }
            val cached = file(context, document.id)
            if (cached.exists()) BitmapFactory.decodeFile(cached.path)?.let { memory.put(document.id, it); return@withPermit it }
            coroutineContext.ensureActive()
            try {
                PdfRenderer(ParcelFileDescriptor.open(File(document.path), ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val width = 240
                        val height = (width.toDouble() * page.height / page.width.coerceAtLeast(1)).roundToInt().coerceIn(1, 360)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        val matrix = Matrix().apply { setRectToRect(RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), RectF(0f, 0f, width.toFloat(), height.toFloat()), Matrix.ScaleToFit.CENTER) }
                        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        coroutineContext.ensureActive()
                        cached.parentFile?.mkdirs()
                        val temporary = File(cached.parentFile, "${document.id}.part")
                        try {
                            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            temporary.renameTo(cached)
                        } finally { temporary.delete() }
                        memory.put(document.id, bitmap)
                        bitmap
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { null }
        }
    }
    suspend fun remove(context: Context, id: String) = withContext(Dispatchers.IO) {
        slots.withPermit { memory.remove(id); file(context, id).delete(); Unit }
    }
}
