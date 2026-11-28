package world.yemyat.slicepilot.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val IMAGE_LOG_TAG = "RemoteImage"

/**
 * Remote image loading for every picture in the app: one pooled HTTP client, a memory cache,
 * a disk cache of the bytes actually needed, and CDN-side resizing.
 *
 * It replaces the per-call `URL.openConnection()` loader that used to live in BrowseScreen.
 * That version paid a fresh TCP+TLS handshake per image and downloaded the site's original
 * (tens of megabytes for a MakerWorld photo) to paint a 56 dp tile, and kept everything in
 * memory only, so each app start fetched the whole gallery again.
 */
private object ImageEngine {
    /** Refuse absurd responses outright; an unbounded readBytes() once OOMed the app. */
    private const val MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024

    /** Total on-disk budget before the oldest cached images are dropped. */
    private const val MAX_DISK_BYTES = 64L * 1024 * 1024

    /** Hard pixel cap on either edge so no remote image can ever exceed Android's 100 MB Canvas limit. */
    private const val MAX_DECODE_DIM = 1600

    private var client: OkHttpClient? = null
    private var memory: LruCache<String, ImageBitmap>? = null
    private var diskRoot: File? = null

    /** Built once from the first composable that needs it; every caller shares the instance. */
    private fun ensure(context: Context): Boolean {
        if (memory != null) return true
        val app = context.applicationContext
        val dir = File(app.cacheDir, "imagecache").apply { mkdirs() }
        if (!dir.isDirectory) return false
        synchronized(this) {
            if (memory == null) {
                memory =
                    object : LruCache<String, ImageBitmap>(
                        minOf((Runtime.getRuntime().maxMemory() / 8).toInt(), 32 * 1024 * 1024),
                    ) {
                        // An ImageBitmap is not an android.graphics.Bitmap, so the size must be
                        // computed here; a wrong sizeOf turns the LRU into an unbounded map.
                        override fun sizeOf(
                            key: String,
                            value: ImageBitmap,
                        ): Int = value.width * value.height * 4
                    }
                // One client means one connection pool: images on the same host reuse a socket
                // instead of handshaking again, which dominated the old per-URL loader.
                client =
                    OkHttpClient
                        .Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .retryOnConnectionFailure(true)
                        .build()
                diskRoot = dir
            }
        }
        return memory != null
    }

    private fun key(
        url: String,
        maxDim: Int,
    ) = "$maxDim|$url"

    /**
     * Ask the CDN for the pixel size we actually want instead of the original photo.
     *
     * MakerWorld serves images from an OSS-style bucket (`*.bblmw.com`) that resists on
     * `x-oss-process`; other hosts (Printables already returns sized variants from its API)
     * are used as given, because an unknown parameter on those yields the original anyway.
     */
    private fun sizedUrl(
        url: String,
        maxDim: Int,
    ): String {
        val width = maxDim.coerceIn(64, 2048)
        return when {
            url.contains("x-oss-process=") -> url
            url.contains("bblmw.com") -> "$url?x-oss-process=image/resize,m_lfit,w_$width"
            else -> url
        }
    }

    /** Memory-only lookup, cheap enough to call during composition. */
    fun peek(
        url: String?,
        maxDim: Int,
    ): ImageBitmap? {
        if (url == null) return null
        return memory?.get(key(url, maxDim))
    }

    /** Load a rendition: memory, then the byte cache on disk, then the network. */
    suspend fun load(
        context: Context,
        url: String?,
        maxDim: Int,
    ): ImageBitmap? {
        if (url == null || !ensure(context)) return null
        peek(url, maxDim)?.let { return it }
        return withContext(Dispatchers.IO) {
            val target = File(diskRoot, digest(key(url, maxDim)))
            decodeFile(target, maxDim)?.let { bitmap ->
                memory?.put(key(url, maxDim), bitmap)
                Log.d(IMAGE_LOG_TAG, "disk   ${maxDim}px ${url.hostOf()} ${target.length()}B")
                return@withContext bitmap
            }
            fetch(url, maxDim, target)
        }
    }

    private fun fetch(
        url: String,
        maxDim: Int,
        target: File,
    ): ImageBitmap? {
        val started = System.currentTimeMillis()
        val request = Request.Builder().url(sizedUrl(url, maxDim)).build()
        return try {
            client!!.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(IMAGE_LOG_TAG, "http ${response.code} for $url")
                    return null
                }
                // null means either no body or a response over the cap; neither is usable.
                val bytes = response.body?.byteStream()?.use { readCapped(it) } ?: return null
                val bitmap = decodeBytes(bytes, maxDim) ?: return null
                writeCache(target, bytes)
                prune()
                memory?.put(key(url, maxDim), bitmap)
                Log.d(
                    IMAGE_LOG_TAG,
                    "net    ${maxDim}px ${url.hostOf()} ${bytes.size}B " +
                        "${System.currentTimeMillis() - started}ms -> ${bitmap.width}x${bitmap.height}",
                )
                bitmap
            }
        } catch (error: OutOfMemoryError) {
            memory?.evictAll()
            Log.w(IMAGE_LOG_TAG, "OOM fetching $url: ${error.message}")
            null
        } catch (error: Exception) {
            Log.w(IMAGE_LOG_TAG, "fetch failed for $url: ${error.message}")
            null
        }
    }

    /** Read at most [MAX_DOWNLOAD_BYTES]; returns null when the response is bigger. */
    private fun readCapped(input: java.io.InputStream): ByteArray? {
        return try {
            val out = java.io.ByteArrayOutputStream(64 * 1024)
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > MAX_DOWNLOAD_BYTES) return null
                out.write(chunk, 0, read)
            }
            out.toByteArray()
        } catch (_: OutOfMemoryError) {
            memory?.evictAll()
            null
        }
    }

    private fun computeSampleSize(
        outWidth: Int,
        outHeight: Int,
        maxDim: Int,
    ): Int {
        val cap = maxDim.coerceIn(64, MAX_DECODE_DIM)
        var sample = 1
        while (outWidth / sample > cap || outHeight / sample > cap) {
            sample *= 2
        }
        return sample
    }

    /** Decode downsampled so neither edge exceeds [maxDim] (and never above [MAX_DECODE_DIM]). */
    private fun decodeBytes(
        bytes: ByteArray,
        maxDim: Int,
    ): ImageBitmap? =
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )?.asImageBitmap()
            }
        } catch (_: OutOfMemoryError) {
            memory?.evictAll()
            null
        }

    private fun decodeFile(
        file: File,
        maxDim: Int,
    ): ImageBitmap? {
        if (!file.isFile || file.length() == 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                null
            } else {
                val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )?.asImageBitmap()
            }
        } catch (_: OutOfMemoryError) {
            memory?.evictAll()
            null
        }
    }

    private fun writeCache(
        file: File,
        bytes: ByteArray,
    ) {
        runCatching { file.outputStream().use { it.write(bytes) } }
    }

    /** Keep the byte cache inside its budget by dropping the oldest files. */
    private fun prune() {
        val files = diskRoot?.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_DISK_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= MAX_DISK_BYTES) break
            total -= file.length()
            runCatching { file.delete() }
        }
    }

    private fun digest(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    private fun String.hostOf(): String = substringAfter("://").substringBefore('/')
}

/** A previously loaded rendition of [url], or null; never triggers a download. */
@Composable
fun cachedImage(
    url: String?,
    maxDimPx: Int,
): ImageBitmap? = remember(url, maxDimPx) { ImageEngine.peek(url, maxDimPx) }

/**
 * Shows [url] at [maxDimPx], keeping [placeholder] on screen until it arrives so an upgrade
 * from a thumbnail to a full-size photo never flashes empty.
 */
@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxDimPx: Int = 192,
    placeholder: ImageBitmap? = cachedImage(url, 192),
) {
    val context = LocalContext.current
    var bitmap by remember(url, maxDimPx) { mutableStateOf(ImageEngine.peek(url, maxDimPx)) }
    if (bitmap == null && url != null) {
        LaunchedEffect(url, maxDimPx) { bitmap = ImageEngine.load(context, url, maxDimPx) }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        (bitmap ?: placeholder)?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
            )
        }
    }
}
