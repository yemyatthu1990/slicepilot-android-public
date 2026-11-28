package world.yemyat.slicepilot.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.annotation.WorkerThread
import world.yemyat.slicepilot.model.GcodePreview
import world.yemyat.slicepilot.model.GcodePreviewParser
import world.yemyat.slicepilot.model.GcodeThumbnail
import world.yemyat.slicepilot.model.PartGeometry
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The picture of a g-code — the same toolpaths the 3D preview shows, flattened onto one
 * small PNG for a list tile.
 *
 * A g-code has no image of its own unless a slicer embedded one, so the app used to show a
 * printer glyph next to a sliced file even though it can draw the print itself. Two sources,
 * in the order they are trusted:
 *
 * 1. the preview a slicer embedded in the file (`; thumbnail begin …`, the block Prusa,
 *    Bambu and [GcodeThumbnail.buildBlock] write) — a shaded render, decoded straight out;
 * 2. the parsed toolpaths drawn flat — what [GcodePreviewView] shows, rasterised at tile
 *    size so any g-code, sliced here or downloaded, gets a picture.
 */
object GcodePreviewImage {
    /** Longest edge of a stored picture: list tiles are 60 dp, and history keeps ten. */
    const val MAX_PX = 192

    /** The plate colour the 3D preview clears to, so the tile matches the preview screen. */
    private const val BACKGROUND = 0xFFE8EAED.toInt()

    /** Toolpath colour: the first extruder's, which is what a single-part print draws in. */
    private const val TOOLPATH = 0xFFF5A623.toInt()

    /** Layers per picture: beyond this the plate is solid anyway, so thin the stack out. */
    private const val MAX_LAYERS = 240

    /** The slicer's embedded preview as PNG bytes, or null when the file carries none. */
    fun embeddedPng(file: File): ByteArray? = GcodeThumbnail.readPng(file)

    /**
     * A PNG for [file], no larger than [maxPx] on its longest edge. Null only when the
     * g-code yields nothing to draw (unreadable, or no toolpaths in it).
     */
    @WorkerThread
    fun renderPng(
        file: File,
        maxPx: Int = MAX_PX,
    ): ByteArray? {
        embeddedPng(file)?.let { embedded ->
            decodeScaled(embedded, maxPx)?.let { return compress(it) }
        }
        return renderToolpaths(file, maxPx)?.let { compress(it) }
    }

    /** The picture of the toolpaths themselves: the plate seen from above. */
    private fun renderToolpaths(
        file: File,
        maxPx: Int,
    ): Bitmap? {
        val preview = runCatching { GcodePreviewParser.parse(file) }.getOrNull() ?: return null
        val width = preview.maxX - preview.minX
        val height = preview.maxY - preview.minY
        if (width <= 0f || height <= 0f) return null

        val pad = maxPx * 0.06f
        val scale = minOf((maxPx - pad) / width, (maxPx - pad) / height)
        val offsetX = (maxPx - width * scale) / 2f
        val offsetY = (maxPx - height * scale) / 2f

        val bitmap = Bitmap.createBitmap(maxPx, maxPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)

        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.7f
                color = TOOLPATH
            }
        // Model Y runs away from the viewer and canvas Y runs down, so Y is flipped; past
        // MAX_LAYERS the plate is solid, so the stack is thinned instead of fully drawn.
        val layers = preview.layers
        val stride = maxOf(1, layers.size / MAX_LAYERS)
        val paths =
            layers
                .filterIndexed { index, _ -> index % stride == 0 }
                .flatMap { layer -> layer.parts.filter { it.vertices.size >= 4 }.map { outline(it, preview, scale, offsetX, offsetY) } }
        if (paths.isEmpty()) {
            // A g-code of nothing but travel moves parses; an outline of nothing is not a preview.
            return null
        }
        paths.forEach { canvas.drawPath(it, paint) }
        return bitmap
    }

    /** One part of one layer as a path, in tile coordinates. */
    private fun outline(
        part: PartGeometry,
        preview: GcodePreview,
        scale: Float,
        offsetX: Float,
        offsetY: Float,
    ): Path {
        val v = part.vertices
        val path = Path()
        for (i in v.indices.step(2)) {
            val x = offsetX + (v[i] - preview.minX) * scale
            val y = offsetY + (v[i + 1] - preview.minY) * scale
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        return path
    }

    /** Decode [bytes] at the cheapest power-of-two size that still covers [maxPx]. */
    private fun decodeScaled(
        bytes: ByteArray,
        maxPx: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        val sample = generateSequence(1) { it * 2 }.first { longest / it <= maxPx * 2 }
        return runCatching {
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
    }

    private fun compress(bitmap: Bitmap): ByteArray? =
        ByteArrayOutputStream().use { out ->
            val ok = runCatching { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }.getOrDefault(false)
            if (ok) out.toByteArray() else null
        }
}
